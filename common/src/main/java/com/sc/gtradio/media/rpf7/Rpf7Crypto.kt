package com.sc.gtradio.media.rpf7

import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * GTA V Key Set container.
 * STRICT LEGAL NOTICE:
 * This class NEVER hardcodes or bundles any raw Rockstar key bytes or decryption tables.
 * All keys must be provided or derived by the user at runtime.
 */
data class Rpf7KeySet(
    val aesKey: ByteArray,
    val lut: ByteArray,
    val ngKeys: Array<ByteArray>,
    val ngDecryptTables: Array<IntArray>,
    val awcKey: IntArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Rpf7KeySet) return false
        if (!aesKey.contentEquals(other.aesKey)) return false
        if (!lut.contentEquals(other.lut)) return false
        if (!ngKeys.contentDeepEquals(other.ngKeys)) return false
        if (!ngDecryptTables.contentDeepEquals(other.ngDecryptTables)) return false
        if (!awcKey.contentEquals(other.awcKey)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = aesKey.contentHashCode()
        result = 31 * result + lut.contentHashCode()
        result = 31 * result + ngKeys.contentDeepHashCode()
        result = 31 * result + ngDecryptTables.contentDeepHashCode()
        result = 31 * result + awcKey.contentHashCode()
        return result
    }
}

/**
 * Pure Kotlin cryptographic primitives for RPF7 archives and audio.
 * Implements:
 * - Jenkins One-at-a-Time Hash
 * - .NET Knuth Subtractive PRNG (System.Random)
 * - AES-256-ECB block decryption (aligned prefix)
 * - 17-Round NG Block Cipher (RPF7 Table of Contents and encrypted resources)
 * - Rockstar RSXXTEA modified block cipher (AWC audio chunks and DJ voice banks)
 */
object Rpf7Crypto {

    /**
     * Jenkins One-at-a-Time Hash matching GTA V PC / CodeWalker implementation.
     */
    fun jenkHash(bytes: ByteArray): Int {
        var h = 0
        for (b in bytes) {
            h += (b.toInt() and 0xFF)
            h += (h shl 10)
            h = h xor (h ushr 6)
        }
        h += (h shl 3)
        h = h xor (h ushr 11)
        h += (h shl 15)
        return h
    }

    /**
     * Jenkins One-at-a-Time Hash of UTF-8 string bytes.
     */
    fun jenkHashString(str: String): Int {
        return jenkHash(str.toByteArray(Charsets.UTF_8))
    }

    /**
     * Exact .NET System.Random (Knuth Subtractive algorithm) PRNG.
     * Emulates 32-bit signed integer overflow semantics.
     */
    class DotNetRandom(seed: Int) {
        companion object {
            private const val MBIG = 2147483647
            private const val MSEED = 161803398
        }

        private val seedArray = IntArray(56)
        private var inext: Int = 0
        private var inextp: Int = 21

        init {
            val subtraction = if (seed == Int.MIN_VALUE) MBIG else Math.abs(seed)
            var mj = MSEED - subtraction
            seedArray[55] = mj
            var mk = 1
            for (i in 1..54) {
                val ii = (21 * i) % 55
                seedArray[ii] = mk
                mk = mj - mk
                if (mk < 0) mk += MBIG
                mj = seedArray[ii]
            }
            for (k in 1..4) {
                for (i in 1..55) {
                    seedArray[i] = seedArray[i] - seedArray[1 + (i + 30) % 55]
                    if (seedArray[i] < 0) seedArray[i] += MBIG
                }
            }
            inext = 0
            inextp = 21
        }

        fun internalSample(): Int {
            var locINext = inext + 1
            var locINextp = inextp + 1
            if (locINext >= 56) locINext = 1
            if (locINextp >= 56) locINextp = 1
            var retVal = seedArray[locINext] - seedArray[locINextp]
            if (retVal == MBIG) retVal--
            if (retVal < 0) retVal += MBIG
            seedArray[locINext] = retVal
            inext = locINext
            inextp = locINextp
            return retVal
        }

        fun nextBytes(buf: ByteArray) {
            for (i in buf.indices) {
                buf[i] = (internalSample() % 256).toByte()
            }
        }
    }

    /**
     * Generates [count] pseudo-random bytes using .NET Knuth Subtractive PRNG seeded with [seed].
     */
    fun dotNetRandomNextBytes(seed: Int, count: Int): ByteArray {
        val buf = ByteArray(count)
        DotNetRandom(seed).nextBytes(buf)
        return buf
    }

    /**
     * AES-256-ECB decrypts the 16-byte-aligned prefix of [data] using [key].
     * Any trailing unaligned bytes are preserved as-is.
     */
    fun decryptAesEcb(data: ByteArray, key: ByteArray): ByteArray {
        val aligned = data.size - (data.size % 16)
        val out = data.copyOf()
        if (aligned > 0) {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            val dec = cipher.doFinal(data, 0, aligned)
            System.arraycopy(dec, 0, out, 0, aligned)
        }
        return out
    }

    /**
     * Unpacks 272-byte NG key into 17 sub-keys of 4 integers each.
     */
    fun unpackNgSubKeys(keyBytes: ByteArray): Array<IntArray> {
        val subKeys = Array(17) { IntArray(4) }
        for (r in 0 until 17) {
            for (i in 0 until 4) {
                val off = (r * 4 + i) * 4
                subKeys[r][i] = (keyBytes[off].toInt() and 0xFF) or
                        ((keyBytes[off + 1].toInt() and 0xFF) shl 8) or
                        ((keyBytes[off + 2].toInt() and 0xFF) shl 16) or
                        ((keyBytes[off + 3].toInt() and 0xFF) shl 24)
            }
        }
        return subKeys
    }

    /**
     * Decrypts a single 16-byte block with 17-round NG cipher into [outBytes] at [outOffset].
     */
    fun decryptNgBlock(
        inBytes: ByteArray,
        inOffset: Int,
        outBytes: ByteArray,
        outOffset: Int,
        subKeys: Array<IntArray>,
        tables: Array<IntArray>
    ) {
        var d0 = inBytes[inOffset].toInt() and 0xFF
        var d1 = inBytes[inOffset + 1].toInt() and 0xFF
        var d2 = inBytes[inOffset + 2].toInt() and 0xFF
        var d3 = inBytes[inOffset + 3].toInt() and 0xFF
        var d4 = inBytes[inOffset + 4].toInt() and 0xFF
        var d5 = inBytes[inOffset + 5].toInt() and 0xFF
        var d6 = inBytes[inOffset + 6].toInt() and 0xFF
        var d7 = inBytes[inOffset + 7].toInt() and 0xFF
        var d8 = inBytes[inOffset + 8].toInt() and 0xFF
        var d9 = inBytes[inOffset + 9].toInt() and 0xFF
        var d10 = inBytes[inOffset + 10].toInt() and 0xFF
        var d11 = inBytes[inOffset + 11].toInt() and 0xFF
        var d12 = inBytes[inOffset + 12].toInt() and 0xFF
        var d13 = inBytes[inOffset + 13].toInt() and 0xFF
        var d14 = inBytes[inOffset + 14].toInt() and 0xFF
        var d15 = inBytes[inOffset + 15].toInt() and 0xFF

        // Rounds 0 and 1: Round A
        for (r in 0..1) {
            val baseTable = r * 16
            val k = subKeys[r]
            val x1 = tables[baseTable + 0][d0] xor tables[baseTable + 1][d1] xor tables[baseTable + 2][d2] xor tables[baseTable + 3][d3] xor k[0]
            val x2 = tables[baseTable + 4][d4] xor tables[baseTable + 5][d5] xor tables[baseTable + 6][d6] xor tables[baseTable + 7][d7] xor k[1]
            val x3 = tables[baseTable + 8][d8] xor tables[baseTable + 9][d9] xor tables[baseTable + 10][d10] xor tables[baseTable + 11][d11] xor k[2]
            val x4 = tables[baseTable + 12][d12] xor tables[baseTable + 13][d13] xor tables[baseTable + 14][d14] xor tables[baseTable + 15][d15] xor k[3]

            d0 = x1 and 0xFF; d1 = (x1 ushr 8) and 0xFF; d2 = (x1 ushr 16) and 0xFF; d3 = (x1 ushr 24) and 0xFF
            d4 = x2 and 0xFF; d5 = (x2 ushr 8) and 0xFF; d6 = (x2 ushr 16) and 0xFF; d7 = (x2 ushr 24) and 0xFF
            d8 = x3 and 0xFF; d9 = (x3 ushr 8) and 0xFF; d10 = (x3 ushr 16) and 0xFF; d11 = (x3 ushr 24) and 0xFF
            d12 = x4 and 0xFF; d13 = (x4 ushr 8) and 0xFF; d14 = (x4 ushr 16) and 0xFF; d15 = (x4 ushr 24) and 0xFF
        }

        // Rounds 2 to 15: Round B
        for (r in 2..15) {
            val baseTable = r * 16
            val k = subKeys[r]
            val x1 = tables[baseTable + 0][d0] xor tables[baseTable + 7][d7] xor tables[baseTable + 10][d10] xor tables[baseTable + 13][d13] xor k[0]
            val x2 = tables[baseTable + 1][d1] xor tables[baseTable + 4][d4] xor tables[baseTable + 11][d11] xor tables[baseTable + 14][d14] xor k[1]
            val x3 = tables[baseTable + 2][d2] xor tables[baseTable + 5][d5] xor tables[baseTable + 8][d8] xor tables[baseTable + 15][d15] xor k[2]
            val x4 = tables[baseTable + 3][d3] xor tables[baseTable + 6][d6] xor tables[baseTable + 9][d9] xor tables[baseTable + 12][d12] xor k[3]

            d0 = x1 and 0xFF; d1 = (x1 ushr 8) and 0xFF; d2 = (x1 ushr 16) and 0xFF; d3 = (x1 ushr 24) and 0xFF
            d4 = x2 and 0xFF; d5 = (x2 ushr 8) and 0xFF; d6 = (x2 ushr 16) and 0xFF; d7 = (x2 ushr 24) and 0xFF
            d8 = x3 and 0xFF; d9 = (x3 ushr 8) and 0xFF; d10 = (x3 ushr 16) and 0xFF; d11 = (x3 ushr 24) and 0xFF
            d12 = x4 and 0xFF; d13 = (x4 ushr 8) and 0xFF; d14 = (x4 ushr 16) and 0xFF; d15 = (x4 ushr 24) and 0xFF
        }

        // Round 16: Round A
        val baseTable16 = 16 * 16
        val k16 = subKeys[16]
        val x1 = tables[baseTable16 + 0][d0] xor tables[baseTable16 + 1][d1] xor tables[baseTable16 + 2][d2] xor tables[baseTable16 + 3][d3] xor k16[0]
        val x2 = tables[baseTable16 + 4][d4] xor tables[baseTable16 + 5][d5] xor tables[baseTable16 + 6][d6] xor tables[baseTable16 + 7][d7] xor k16[1]
        val x3 = tables[baseTable16 + 8][d8] xor tables[baseTable16 + 9][d9] xor tables[baseTable16 + 10][d10] xor tables[baseTable16 + 11][d11] xor k16[2]
        val x4 = tables[baseTable16 + 12][d12] xor tables[baseTable16 + 13][d13] xor tables[baseTable16 + 14][d14] xor tables[baseTable16 + 15][d15] xor k16[3]

        outBytes[outOffset] = (x1 and 0xFF).toByte()
        outBytes[outOffset + 1] = ((x1 ushr 8) and 0xFF).toByte()
        outBytes[outOffset + 2] = ((x1 ushr 16) and 0xFF).toByte()
        outBytes[outOffset + 3] = ((x1 ushr 24) and 0xFF).toByte()

        outBytes[outOffset + 4] = (x2 and 0xFF).toByte()
        outBytes[outOffset + 5] = ((x2 ushr 8) and 0xFF).toByte()
        outBytes[outOffset + 6] = ((x2 ushr 16) and 0xFF).toByte()
        outBytes[outOffset + 7] = ((x2 ushr 24) and 0xFF).toByte()

        outBytes[outOffset + 8] = (x3 and 0xFF).toByte()
        outBytes[outOffset + 9] = ((x3 ushr 8) and 0xFF).toByte()
        outBytes[outOffset + 10] = ((x3 ushr 16) and 0xFF).toByte()
        outBytes[outOffset + 11] = ((x3 ushr 24) and 0xFF).toByte()

        outBytes[outOffset + 12] = (x4 and 0xFF).toByte()
        outBytes[outOffset + 13] = ((x4 ushr 8) and 0xFF).toByte()
        outBytes[outOffset + 14] = ((x4 ushr 16) and 0xFF).toByte()
        outBytes[outOffset + 15] = ((x4 ushr 24) and 0xFF).toByte()
    }

    /**
     * Calculates the NG key index according to standard CodeWalker formula:
     * (jenkHash(lower(name)) + length + 61) % 101.
     */
    fun getNgKeyIndex(rpfName: String, fileSize: Long): Int {
        val h = jenkHash(rpfName.lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
        val rem = (h.toLong() and 0xFFFFFFFFL) + fileSize + 61L
        val mod = (rem % 101L).toInt()
        return if (mod < 0) mod + 101 else mod
    }

    /**
     * Determines the exact valid NG key index for an RPF7 Table of Contents.
     * Checks the standard formula first; if the root entry marker does not match 0x7FFFFF00,
     * quickly brute-forces all 101 keys against the first 16-byte block.
     */
    fun findValidRpf7TocKeyIndex(
        firstBlockEncrypted: ByteArray,
        rpfName: String,
        fileSize: Long,
        keys: Rpf7KeySet
    ): Int {
        if (firstBlockEncrypted.size < 16) return 0
        val formulaIndex = getNgKeyIndex(rpfName, fileSize)
        val tempBlock = ByteArray(16)

        // 1. Try calculated formula index
        val subKFormula = unpackNgSubKeys(keys.ngKeys[formulaIndex % keys.ngKeys.size])
        decryptNgBlock(firstBlockEncrypted, 0, tempBlock, 0, subKFormula, keys.ngDecryptTables)
        val marker = (tempBlock[4].toInt() and 0xFF) or
                ((tempBlock[5].toInt() and 0xFF) shl 8) or
                ((tempBlock[6].toInt() and 0xFF) shl 16) or
                ((tempBlock[7].toInt() and 0xFF) shl 24)
        if (marker == 0x7FFFFF00) {
            return formulaIndex
        }

        // 2. Fallback brute-force against all 101 keys
        for (trial in 0 until keys.ngKeys.size) {
            if (trial == formulaIndex) continue
            val subKTrial = unpackNgSubKeys(keys.ngKeys[trial])
            decryptNgBlock(firstBlockEncrypted, 0, tempBlock, 0, subKTrial, keys.ngDecryptTables)
            val trialMarker = (tempBlock[4].toInt() and 0xFF) or
                    ((tempBlock[5].toInt() and 0xFF) shl 8) or
                    ((tempBlock[6].toInt() and 0xFF) shl 16) or
                    ((tempBlock[7].toInt() and 0xFF) shl 24)
            if (trialMarker == 0x7FFFFF00) {
                return trial
            }
        }
        return formulaIndex
    }

    /**
     * Decrypts [data] with the 17-round NG cipher using specific [keyIndex].
     */
    fun decryptNGWithKeyIndex(data: ByteArray, keyIndex: Int, keys: Rpf7KeySet): ByteArray {
        val keyBytes = keys.ngKeys[keyIndex % keys.ngKeys.size]
        val subKeys = unpackNgSubKeys(keyBytes)
        val out = ByteArray(data.size)
        val numBlocks = data.size / 16
        for (b in 0 until numBlocks) {
            decryptNgBlock(data, b * 16, out, b * 16, subKeys, keys.ngDecryptTables)
        }
        val rem = data.size % 16
        if (rem > 0) {
            val off = numBlocks * 16
            System.arraycopy(data, off, out, off, rem)
        }
        return out
    }

    /**
     * Decrypts [data] using RPF name and file size, automatically resolving the valid key index.
     */
    fun decryptNG(data: ByteArray, rpfName: String, fileSize: Long, keys: Rpf7KeySet): ByteArray {
        val keyIdx = if (data.size >= 16) {
            findValidRpf7TocKeyIndex(data, rpfName, fileSize, keys)
        } else {
            getNgKeyIndex(rpfName, fileSize)
        }
        return decryptNGWithKeyIndex(data, keyIdx, keys)
    }

    /**
     * In-place Rockstar XXTEA (RSXXTEA) decryption matching AwcFile.cs.
     * Decrypts [length] bytes of [data] starting at [offset] using 16-byte [awcKey] (4 uint32s).
     * Used for AWC MultiChannel encrypted stream blocks and SingleChannel encrypted clips.
     */
    fun decryptRsxxtea(data: ByteArray, offset: Int, length: Int, awcKey: IntArray) {
        if (length <= 0 || offset < 0 || offset + length > data.size) return
        val blockCount = length / 4
        if (blockCount == 0) return

        try {
            com.sc.gtradio.media.core.Gen3NativeTurbo.nativeDecryptAwcXxtea(data, offset, length, awcKey)
            return
        } catch (_: Throwable) {
            // Fallback to pure Kotlin implementation below if native library is unavailable
        }

        val blocks = IntArray(blockCount)
        for (k in 0 until blockCount) {
            val o = offset + k * 4
            blocks[k] = (data[o].toInt() and 0xFF) or
                    ((data[o + 1].toInt() and 0xFF) shl 8) or
                    ((data[o + 2].toInt() and 0xFF) shl 16) or
                    ((data[o + 3].toInt() and 0xFF) shl 24)
        }

        var b = blocks[0]
        val delta = 0x9E3779B9.toInt()
        var sum = delta * (6 + 52 / blockCount)

        while (sum != 0) {
            for (blockIdx in blockCount - 1 downTo 0) {
                val a = if (blockIdx > 0) blocks[blockIdx - 1] else blocks[blockCount - 1]
                val mx = (((a ushr 5) xor (b shl 2)) + ((b ushr 3) xor (a shl 4))) xor
                        ((sum xor b) + (awcKey[(blockIdx and 3) xor ((sum ushr 2) and 3)] xor a xor 0x7B3A207F))
                b = blocks[blockIdx] - mx
                blocks[blockIdx] = b
            }
            sum -= delta
        }

        for (k in 0 until blockCount) {
            val o = offset + k * 4
            val v = blocks[k]
            data[o] = (v and 0xFF).toByte()
            data[o + 1] = ((v ushr 8) and 0xFF).toByte()
            data[o + 2] = ((v ushr 16) and 0xFF).toByte()
            data[o + 3] = ((v ushr 24) and 0xFF).toByte()
        }
    }
}
