package com.sc.gtradio.media.stations

import android.content.Context
import android.os.Looper
import androidx.documentfile.provider.DocumentFile
import com.sc.gtradio.common.GTRadioLogger
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater

/**
 * Result of importing GTA V keys.
 */
sealed class ImportResult {
    data class Success(val keySet: GtaVKeySet, val source: String) : ImportResult()
    data class Error(val message: String, val cause: Throwable? = null) : ImportResult()
    object MissingMagicDat : ImportResult()
    object NoKeysFound : ImportResult()
}

/**
 * Thread-safe singleton managing GTA V Legacy PC keys privately in internal storage.
 *
 * STRICT LEGAL NOTICE:
 * This file NEVER contains, hardcodes, or bundles any raw Rockstar key bytes or decryption tables.
 * Only public SHA-1 verification hashes from open-source references (CodeWalker) are stored.
 */
object GtaVKeyManager {

    // Public verification SHA-1 hashes (never store raw key bytes in code!)
    const val PC_AES_KEY_SHA1 = "a0796128a775720ac204d9819f68c172e3952c6d"
    const val PC_LUT_SHA1 = "88d3793b8e7a6cacaa8b892897be728d9e7fbad4"
    const val PC_NG_KEY_0_SHA1 = "eb0915120397ce2e17828dd77e3218d977a185f7"
    const val PC_NG_DECRYPT_TABLE_0_SHA1 = "ceab9716bdec4eb6b028d35639e45f2f3d8ac03a"

    // Known PE raw offsets for GTA5.exe Legacy and Enhanced PC builds
    private const val KNOWN_OFFSET_AES_KEY = 0x01E34C98L
    private const val KNOWN_OFFSET_AES_KEY_ENHANCED = 0x0289C3E0L
    private const val KNOWN_OFFSET_LUT = 0x01B7BCC0L
    private const val KNOWN_OFFSET_LUT_ENHANCED = 0x02488880L

    private const val MAGIC_DAT_URL =
        "https://raw.githubusercontent.com/dexyfex/CodeWalker/master/CodeWalker.Core/Resources/magic.dat"

    @Volatile
    private var cachedKeySet: GtaVKeySet? = null

    /**
     * Checks if valid keys are currently loaded or available in private storage.
     */
    fun isUnlocked(context: Context): Boolean {
        if (cachedKeySet != null) return true
        return getKeys(context) != null
    }

    /**
     * Retrieves the loaded [GtaVKeySet], or attempts to load from private storage.
     */
    @Synchronized
    fun getKeys(context: Context): GtaVKeySet? {
        cachedKeySet?.let { return it }

        // Path A: Load from private cache in filesDir/gtav_keys/
        val keySet = loadFromPrivateCache(context)
        if (keySet != null) {
            cachedKeySet = keySet
            return keySet
        }
        return null
    }

    /**
     * Alias for [getKeys]. Ensures keys are loaded if available.
     */
    fun ensureKeysLoaded(context: Context): GtaVKeySet? = getKeys(context)

    /**
     * Imports keys from a user-selected folder:
     * 1. Checks for pre-extracted CodeWalker .dat files (gtav_aes_key.dat, etc.)
     * 2. Checks for GTA5.exe + magic.dat (deriving NG keys and decrypt tables)
     */
    @Synchronized
    fun importFromFolder(
        context: Context,
        folderDoc: DocumentFile,
        allowMagicDownload: Boolean = false
    ): ImportResult {
        GTRadioLogger.log("GtaVKeyManager: Scanning folder for keys: ${folderDoc.name} (${folderDoc.uri})")

        // 1. Try importing pre-extracted .dat files
        val datResult = tryImportDatFiles(context, folderDoc)
        if (datResult is ImportResult.Success) {
            cachedKeySet = datResult.keySet
            return datResult
        }

        // 2. Try importing from GTA5.exe + magic.dat
        val exeResult = tryImportFromExeAndMagic(context, folderDoc, allowMagicDownload)
        if (exeResult is ImportResult.Success) {
            cachedKeySet = exeResult.keySet
            return exeResult
        }

        return if (exeResult is ImportResult.MissingMagicDat) {
            exeResult
        } else if (datResult is ImportResult.Error) {
            datResult
        } else if (exeResult is ImportResult.Error) {
            exeResult
        } else {
            ImportResult.NoKeysFound
        }
    }

    /**
     * Alias for [importFromFolder].
     */
    fun importKeysFromFolder(
        context: Context,
        folderDoc: DocumentFile,
        allowMagicDownload: Boolean = false
    ): ImportResult = importFromFolder(context, folderDoc, allowMagicDownload)

    /**
     * Returns a human-readable status summary.
     */
    fun getKeyStatusSummary(context: Context): String {
        val keys = getKeys(context)
        return if (keys != null) {
            "Unlocked (101 NG keys, 272 decrypt tables, AWC key: ${if (keys.awcKey.isNotEmpty()) "Available" else "Missing"})"
        } else {
            "Locked (GTA V keys not found or not imported)"
        }
    }

    /**
     * Clears in-memory keys and deletes private cache files.
     */
    @Synchronized
    fun clearCachedKeys(context: Context): Boolean {
        cachedKeySet = null
        val keysDir = File(context.filesDir, "gtav_keys")
        if (!keysDir.exists()) return true
        var allDeleted = true
        keysDir.listFiles()?.forEach { f ->
            if (!f.delete()) allDeleted = false
        }
        GTRadioLogger.log("GtaVKeyManager: Cleared cached keys (success=$allDeleted)")
        return allDeleted
    }

    /**
     * Helper to download CodeWalker's public magic.dat into filesDir/gtav_keys/magic.dat.
     * Only executed when explicitly triggered by user or setup.
     * Safely dispatches to a background executor if invoked from the main thread.
     */
    fun downloadPublicMagicDat(context: Context): File? {
        val keysDir = File(context.filesDir, "gtav_keys")
        if (!keysDir.exists()) keysDir.mkdirs()
        val targetFile = File(keysDir, "magic.dat")
        val tempFile = File(keysDir, "magic.dat.download.tmp")

        if (Looper.myLooper() == Looper.getMainLooper()) {
            return try {
                val executor = Executors.newSingleThreadExecutor()
                try {
                    executor.submit(Callable {
                        downloadPublicMagicDatInternal(targetFile, tempFile)
                    }).get(15, TimeUnit.SECONDS)
                } finally {
                    executor.shutdown()
                }
            } catch (e: Exception) {
                GTRadioLogger.log("GtaVKeyManager: Background download of magic.dat failed or timed out", e)
                null
            }
        } else {
            return downloadPublicMagicDatInternal(targetFile, tempFile)
        }
    }

    private fun downloadPublicMagicDatInternal(targetFile: File, tempFile: File): File? {
        try {
            GTRadioLogger.log("GtaVKeyManager: Downloading public magic.dat from GitHub...")
            val url = URL(MAGIC_DAT_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.connect()

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                GTRadioLogger.log("GtaVKeyManager: Download failed with HTTP ${conn.responseCode}")
                return null
            }

            conn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            if (tempFile.length() > 0) {
                if (targetFile.exists()) targetFile.delete()
                if (tempFile.renameTo(targetFile)) {
                    GTRadioLogger.log("GtaVKeyManager: magic.dat downloaded successfully (${targetFile.length()} bytes)")
                    return targetFile
                }
            }
        } catch (e: Exception) {
            GTRadioLogger.log("GtaVKeyManager: Failed downloading magic.dat", e)
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
        return null
    }

    // ------------------------------------------------------------------------
    // Path A: Load from private cache files
    // ------------------------------------------------------------------------
    private fun loadFromPrivateCache(context: Context): GtaVKeySet? {
        val keysDir = File(context.filesDir, "gtav_keys")
        if (!keysDir.exists()) return null

        val fAes = File(keysDir, "gtav_aes_key.dat")
        val fLut = File(keysDir, "gtav_lut.dat")
        val fNgKeys = File(keysDir, "gtav_ng_keys.dat")
        val fNgTables = File(keysDir, "gtav_ng_tables.dat")
        val fAwcKey = File(keysDir, "gtav_awc_key.dat")

        if (!fAes.exists() || !fLut.exists() || !fNgKeys.exists() || !fNgTables.exists() || !fAwcKey.exists()) {
            return null
        }

        try {
            val aesBytes = fAes.readBytes()
            val lutBytes = fLut.readBytes()
            val ngKeysBytes = fNgKeys.readBytes()
            val ngTablesBytes = fNgTables.readBytes()
            val awcKeyBytes = fAwcKey.readBytes()

            if (aesBytes.size != 32 || lutBytes.size != 256 ||
                ngKeysBytes.size != 101 * 272 || ngTablesBytes.size != 272 * 1024 ||
                awcKeyBytes.size != 16
            ) {
                GTRadioLogger.log("GtaVKeyManager: Private key cache size mismatch")
                return null
            }

            // Verify SHA-1 hashes
            if (!sha1Hex(aesBytes).equals(PC_AES_KEY_SHA1, ignoreCase = true) ||
                !sha1Hex(lutBytes).equals(PC_LUT_SHA1, ignoreCase = true) ||
                !sha1Hex(ngKeysBytes, 0, 272).equals(PC_NG_KEY_0_SHA1, ignoreCase = true) ||
                !sha1Hex(ngTablesBytes, 0, 1024).equals(PC_NG_DECRYPT_TABLE_0_SHA1, ignoreCase = true)
            ) {
                GTRadioLogger.log("GtaVKeyManager: Private key cache SHA-1 verification failed")
                return null
            }

            val keySet = buildKeySet(aesBytes, lutBytes, ngKeysBytes, ngTablesBytes, awcKeyBytes)
            GTRadioLogger.log("GtaVKeyManager: Successfully loaded keys from private cache")
            return keySet
        } catch (e: Exception) {
            GTRadioLogger.log("GtaVKeyManager: Failed loading keys from private cache", e)
            return null
        }
    }

    // ------------------------------------------------------------------------
    // Path B: Pre-extracted CodeWalker .dat files
    // ------------------------------------------------------------------------
    private fun tryImportDatFiles(context: Context, folderDoc: DocumentFile): ImportResult {
        val files = folderDoc.listFiles()
        val docMap = HashMap<String, DocumentFile>()
        for (f in files) {
            f.name?.lowercase(Locale.ROOT)?.let { docMap[it] = f }
        }

        val aesDoc = docMap["gtav_aes_key.dat"] ?: return ImportResult.NoKeysFound
        val ngKeysDoc = docMap["gtav_ng_key.dat"] ?: docMap["gtav_ng_keys.dat"] ?: return ImportResult.NoKeysFound
        val ngTablesDoc = docMap["gtav_ng_decrypt_tables.dat"] ?: docMap["gtav_ng_tables.dat"] ?: return ImportResult.NoKeysFound
        val lutDoc = docMap["gtav_hash_lut.dat"] ?: docMap["gtav_lut.dat"] ?: return ImportResult.NoKeysFound
        val awcDoc = docMap["gtav_awc_key.dat"]

        try {
            val aesBytes = readDocFully(context, aesDoc)
            val lutBytes = readDocFully(context, lutDoc)
            val ngKeysBytes = readDocFully(context, ngKeysDoc)
            val ngTablesBytes = readDocFully(context, ngTablesDoc)
            val awcKeyBytes = if (awcDoc != null) readDocFully(context, awcDoc) else ByteArray(16)

            if (aesBytes.size != 32 || lutBytes.size != 256 ||
                ngKeysBytes.size != 101 * 272 || ngTablesBytes.size != 272 * 1024
            ) {
                return ImportResult.Error("Pre-extracted .dat files have incorrect byte sizes")
            }

            if (!sha1Hex(aesBytes).equals(PC_AES_KEY_SHA1, ignoreCase = true) ||
                !sha1Hex(lutBytes).equals(PC_LUT_SHA1, ignoreCase = true) ||
                !sha1Hex(ngKeysBytes, 0, 272).equals(PC_NG_KEY_0_SHA1, ignoreCase = true) ||
                !sha1Hex(ngTablesBytes, 0, 1024).equals(PC_NG_DECRYPT_TABLE_0_SHA1, ignoreCase = true)
            ) {
                return ImportResult.Error("Pre-extracted .dat files failed SHA-1 verification")
            }

            saveToPrivateCache(context, aesBytes, lutBytes, ngKeysBytes, ngTablesBytes, awcKeyBytes)
            val keySet = buildKeySet(aesBytes, lutBytes, ngKeysBytes, ngTablesBytes, awcKeyBytes)
            GTRadioLogger.log("GtaVKeyManager: Successfully imported CodeWalker .dat files")
            return ImportResult.Success(keySet, "CodeWalker .dat files")
        } catch (e: Exception) {
            return ImportResult.Error("Failed reading .dat files: ${e.message}", e)
        }
    }

    // ------------------------------------------------------------------------
    // Path C: GTA5.exe + magic.dat derivation
    // ------------------------------------------------------------------------
    private fun tryImportFromExeAndMagic(
        context: Context,
        folderDoc: DocumentFile,
        allowMagicDownload: Boolean
    ): ImportResult {
        val files = folderDoc.listFiles()
        var exeDoc: DocumentFile? = null
        var magicDoc: DocumentFile? = null

        for (f in files) {
            val name = f.name?.lowercase(Locale.ROOT) ?: continue
            if (exeDoc == null && name.endsWith(".exe") && (name == "gta5.exe" || name == "gta5_enhanced.exe" || name.contains("gta5"))) {
                exeDoc = f
            }
            if (magicDoc == null && name == "magic.dat") {
                magicDoc = f
            }
        }

        if (exeDoc == null) {
            return ImportResult.NoKeysFound
        }

        val exeName = exeDoc.name ?: "GTA5.exe"

        // Check for magic.dat in folder, then private cache, or download if permitted
        var magicBytes: ByteArray? = null
        if (magicDoc != null) {
            try {
                magicBytes = readDocFully(context, magicDoc)
                val keysDir = File(context.filesDir, "gtav_keys")
                if (!keysDir.exists()) keysDir.mkdirs()
                val localMagic = File(keysDir, "magic.dat")
                if (!localMagic.exists() || localMagic.length() != magicBytes.size.toLong()) {
                    localMagic.writeBytes(magicBytes)
                }
            } catch (_: Exception) {}
        }
        if (magicBytes == null) {
            val localMagic = File(context.filesDir, "gtav_keys/magic.dat")
            if (localMagic.exists() && localMagic.length() > 0) {
                magicBytes = localMagic.readBytes()
            }
        }
        if (magicBytes == null && allowMagicDownload) {
            val downloaded = downloadPublicMagicDat(context)
            if (downloaded != null && downloaded.exists()) {
                magicBytes = downloaded.readBytes()
            }
        }

        if (magicBytes == null) {
            GTRadioLogger.log("GtaVKeyManager: $exeName found but magic.dat is missing")
            return ImportResult.MissingMagicDat
        }

        // Extract AES Key & LUT from GTA5.exe / GTA5_Enhanced.exe
        val (aesKey, lutFromExe) = try {
            extractKeysFromExe(context, exeDoc)
        } catch (e: Exception) {
            return ImportResult.Error("Failed scanning $exeName: ${e.message}", e)
        }

        if (aesKey == null) {
            return ImportResult.Error("PC_AES_KEY was not found inside $exeName")
        }

        // Derive NG keys, decrypt tables, LUT, AWC key using magic.dat
        return try {
            val keySet = deriveKeysWithMagic(context, aesKey, magicBytes, lutFromExe)
            ImportResult.Success(keySet, "Extracted from $exeName + magic.dat")
        } catch (e: Exception) {
            ImportResult.Error("Key derivation from magic.dat failed: ${e.message}", e)
        }
    }

    /**
     * Extracts PC_AES_KEY and PC_LUT from GTA5.exe or GTA5_Enhanced.exe.
     * Uses fast-path known offsets first, falling back to buffered PE section streaming.
     */
    private fun extractKeysFromExe(context: Context, exeDoc: DocumentFile): Pair<ByteArray?, ByteArray?> {
        var foundAes: ByteArray? = null
        var foundLut: ByteArray? = null

        context.contentResolver.openFileDescriptor(exeDoc.uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                val size = channel.size()

                // Fast path: Known offsets (Legacy and Enhanced)
                val candidateAesOffsets = longArrayOf(KNOWN_OFFSET_AES_KEY, KNOWN_OFFSET_AES_KEY_ENHANCED)
                for (off in candidateAesOffsets) {
                    if (off + 32 <= size) {
                        val bufAes = ByteBuffer.allocate(32)
                        channel.position(off)
                        channel.read(bufAes)
                        val candidate = bufAes.array()
                        if (sha1Hex(candidate).equals(PC_AES_KEY_SHA1, ignoreCase = true)) {
                            foundAes = candidate
                            GTRadioLogger.log("GtaVKeyManager: PC_AES_KEY found at fast-path offset 0x${off.toString(16)}")
                            break
                        }
                    }
                }

                val candidateLutOffsets = longArrayOf(KNOWN_OFFSET_LUT, KNOWN_OFFSET_LUT_ENHANCED)
                for (off in candidateLutOffsets) {
                    if (off + 256 <= size) {
                        val bufLut = ByteBuffer.allocate(256)
                        channel.position(off)
                        channel.read(bufLut)
                        val candidate = bufLut.array()
                        if (sha1Hex(candidate).equals(PC_LUT_SHA1, ignoreCase = true)) {
                            foundLut = candidate
                            GTRadioLogger.log("GtaVKeyManager: PC_LUT found at fast-path offset 0x${off.toString(16)}")
                            break
                        }
                    }
                }

                if (foundAes != null) {
                    if (foundLut == null) {
                        GTRadioLogger.log("GtaVKeyManager: PC_LUT not found at fast-path offsets; will source directly from magic.dat")
                    }
                    return Pair(foundAes, foundLut)
                }

                // Fallback: Stream PE sections (.data and .rdata)
                GTRadioLogger.log("GtaVKeyManager: Fast path miss; scanning PE sections...")
                val peSections = parsePeSections(channel)
                for (sec in peSections) {
                    if (foundAes != null && foundLut != null) break
                    val name = sec.name.lowercase(Locale.ROOT)
                    if (name.startsWith(".data") || name.startsWith(".rdata")) {
                        scanSectionForKeys(channel, sec, foundAes == null, foundLut == null) { aes, lut ->
                            if (aes != null) foundAes = aes
                            if (lut != null) foundLut = lut
                        }
                    }
                }
            }
        }

        return Pair(foundAes, foundLut)
    }

    private data class PeSection(
        val name: String,
        val rawPtr: Long,
        val rawSize: Long
    )

    private fun parsePeSections(channel: FileChannel): List<PeSection> {
        val list = ArrayList<PeSection>()
        val buf = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)

        // Read DOS header
        channel.position(0)
        channel.read(buf)
        buf.flip()
        if (buf.remaining() < 64 || buf.get() != 'M'.toByte() || buf.get() != 'Z'.toByte()) {
            return list
        }

        buf.position(0x3C)
        val peOffset = buf.int.toLong()
        if (peOffset <= 0 || peOffset + 24 > channel.size()) return list

        // Read PE header
        buf.clear()
        buf.limit(24)
        channel.position(peOffset)
        channel.read(buf)
        buf.flip()

        val peSig = buf.int
        if (peSig != 0x00004550) return list // "PE\0\0"

        val numSections = buf.getShort(6).toInt() and 0xFFFF
        val optHeaderSize = buf.getShort(20).toInt() and 0xFFFF
        val sectionTableOffset = peOffset + 24 + optHeaderSize

        val secBuf = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until numSections) {
            secBuf.clear()
            channel.position(sectionTableOffset + i * 40L)
            if (channel.read(secBuf) < 40) break
            secBuf.flip()

            val nameBytes = ByteArray(8)
            secBuf.get(nameBytes)
            val nameStr = String(nameBytes, Charsets.US_ASCII).trimEnd('\u0000')

            secBuf.position(16)
            val rawSize = secBuf.int.toLong() and 0xFFFFFFFFL
            val rawPtr = secBuf.int.toLong() and 0xFFFFFFFFL

            list.add(PeSection(nameStr, rawPtr, rawSize))
        }

        return list
    }

    private fun scanSectionForKeys(
        channel: FileChannel,
        section: PeSection,
        needAes: Boolean,
        needLut: Boolean,
        onFound: (ByteArray?, ByteArray?) -> Unit
    ) {
        val chunkSize = 1024 * 1024 // 1 MB buffer
        val overlap = 256
        val buf = ByteBuffer.allocate(chunkSize)
        val rawBytes = ByteArray(chunkSize)

        var curAes: ByteArray? = null
        var curLut: ByteArray? = null
        var filePos = section.rawPtr
        val endPos = section.rawPtr + section.rawSize

        while (filePos < endPos && (needAes && curAes == null || needLut && curLut == null)) {
            buf.clear()
            val toRead = Math.min(chunkSize.toLong(), endPos - filePos).toInt()
            buf.limit(toRead)
            channel.position(filePos)
            val bytesRead = channel.read(buf)
            if (bytesRead <= 0) break

            buf.flip()
            buf.get(rawBytes, 0, bytesRead)

            // Scan 8-byte aligned offsets
            val maxOffset = bytesRead - 32
            var off = 0
            while (off <= maxOffset) {
                if (needAes && curAes == null && off + 32 <= bytesRead) {
                    if (sha1Hex(rawBytes, off, 32).equals(PC_AES_KEY_SHA1, ignoreCase = true)) {
                        curAes = rawBytes.copyOfRange(off, off + 32)
                        GTRadioLogger.log("GtaVKeyManager: PC_AES_KEY found in ${section.name} at 0x${(filePos + off).toString(16)}")
                    }
                }
                if (needLut && curLut == null && off + 256 <= bytesRead) {
                    if (sha1Hex(rawBytes, off, 256).equals(PC_LUT_SHA1, ignoreCase = true)) {
                        curLut = rawBytes.copyOfRange(off, off + 256)
                        GTRadioLogger.log("GtaVKeyManager: PC_LUT found in ${section.name} at 0x${(filePos + off).toString(16)}")
                    }
                }
                off += 8
            }

            if (curAes != null && (!needLut || curLut != null)) break
            filePos += (bytesRead - overlap)
        }

        onFound(curAes, curLut)
    }

    /**
     * Derives all NG keys and decrypt tables using PC_AES_KEY and magic.dat.
     */
    private fun deriveKeysWithMagic(
        context: Context,
        aesKey: ByteArray,
        magicBytes: ByteArray,
        lutFromExe: ByteArray?
    ): GtaVKeySet {
        val n = magicBytes.size
        val seed = GtaVCrypto.jenkHash(aesKey)
        val rng = GtaVCrypto.DotNetRandom(seed)

        val rb1 = ByteArray(n); rng.nextBytes(rb1)
        val rb2 = ByteArray(n); rng.nextBytes(rb2)
        val rb3 = ByteArray(n); rng.nextBytes(rb3)
        val rb4 = ByteArray(n); rng.nextBytes(rb4)

        val db = ByteArray(n)
        for (i in 0 until n) {
            val v = (magicBytes[i].toInt() and 0xFF) -
                    (rb1[i].toInt() and 0xFF) -
                    (rb2[i].toInt() and 0xFF) -
                    (rb3[i].toInt() and 0xFF) -
                    (rb4[i].toInt() and 0xFF)
            db[i] = v.toByte()
        }

        val dec = GtaVCrypto.decryptAesEcb(db, aesKey)

        // Raw Deflate inflation (Inflater(true))
        val inflater = Inflater(true)
        inflater.setInput(dec)
        val baos = ByteArrayOutputStream(306272)
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val count = inflater.inflate(buf)
            if (count == 0) {
                if (inflater.needsInput() || inflater.finished()) break
                break
            }
            baos.write(buf, 0, count)
        }
        inflater.end()

        val inflated = baos.toByteArray()
        if (inflated.size < 306272) {
            throw IllegalStateException("Inflated magic data size ${inflated.size} is less than required 306272 bytes")
        }

        val ngKeysBytes = inflated.copyOfRange(0, 27472)
        val ngTablesBytes = inflated.copyOfRange(27472, 306000)
        val lutBytes = inflated.copyOfRange(306000, 306256)
        val awcKeyBytes = inflated.copyOfRange(306256, 306272)

        // Verify SHA-1 hashes
        if (!sha1Hex(ngKeysBytes, 0, 272).equals(PC_NG_KEY_0_SHA1, ignoreCase = true)) {
            throw IllegalStateException("Derived PC_NG_KEYS failed SHA-1 verification")
        }
        if (!sha1Hex(ngTablesBytes, 0, 1024).equals(PC_NG_DECRYPT_TABLE_0_SHA1, ignoreCase = true)) {
            throw IllegalStateException("Derived PC_NG_DECRYPT_TABLES failed SHA-1 verification")
        }
        if (!sha1Hex(lutBytes).equals(PC_LUT_SHA1, ignoreCase = true)) {
            throw IllegalStateException("Derived PC_LUT failed SHA-1 verification")
        }

        val finalLut = if (lutFromExe != null && sha1Hex(lutFromExe).equals(PC_LUT_SHA1, ignoreCase = true)) {
            lutFromExe
        } else {
            lutBytes
        }
        saveToPrivateCache(context, aesKey, finalLut, ngKeysBytes, ngTablesBytes, awcKeyBytes)

        val keySet = buildKeySet(aesKey, finalLut, ngKeysBytes, ngTablesBytes, awcKeyBytes)
        GTRadioLogger.log("GtaVKeyManager: Successfully derived all GTA V keys from magic.dat")
        return keySet
    }

    private fun saveToPrivateCache(
        context: Context,
        aes: ByteArray,
        lut: ByteArray,
        ngKeys: ByteArray,
        ngTables: ByteArray,
        awcKey: ByteArray
    ) {
        val keysDir = File(context.filesDir, "gtav_keys")
        if (!keysDir.exists()) keysDir.mkdirs()

        File(keysDir, "gtav_aes_key.dat").writeBytes(aes)
        File(keysDir, "gtav_lut.dat").writeBytes(lut)
        File(keysDir, "gtav_ng_keys.dat").writeBytes(ngKeys)
        File(keysDir, "gtav_ng_tables.dat").writeBytes(ngTables)
        File(keysDir, "gtav_awc_key.dat").writeBytes(awcKey)
    }

    private fun buildKeySet(
        aes: ByteArray,
        lut: ByteArray,
        ngKeysBytes: ByteArray,
        ngTablesBytes: ByteArray,
        awcKeyBytes: ByteArray
    ): GtaVKeySet {
        val ngKeys = Array(101) { i ->
            ngKeysBytes.copyOfRange(i * 272, (i + 1) * 272)
        }

        val ngTables = Array(272) { t ->
            val table = IntArray(256)
            val base = t * 1024
            for (k in 0 until 256) {
                val off = base + k * 4
                table[k] = (ngTablesBytes[off].toInt() and 0xFF) or
                        ((ngTablesBytes[off + 1].toInt() and 0xFF) shl 8) or
                        ((ngTablesBytes[off + 2].toInt() and 0xFF) shl 16) or
                        ((ngTablesBytes[off + 3].toInt() and 0xFF) shl 24)
            }
            table
        }

        val awcKey = IntArray(4)
        for (k in 0 until 4) {
            val off = k * 4
            awcKey[k] = (awcKeyBytes[off].toInt() and 0xFF) or
                    ((awcKeyBytes[off + 1].toInt() and 0xFF) shl 8) or
                    ((awcKeyBytes[off + 2].toInt() and 0xFF) shl 16) or
                    ((awcKeyBytes[off + 3].toInt() and 0xFF) shl 24)
        }

        return GtaVKeySet(aes, lut, ngKeys, ngTables, awcKey)
    }

    private fun readDocFully(context: Context, doc: DocumentFile): ByteArray {
        context.contentResolver.openInputStream(doc.uri)?.use { stream: InputStream ->
            return stream.readBytes()
        }
        throw IllegalStateException("Unable to open input stream for ${doc.name}")
    }

    private fun sha1Hex(data: ByteArray, offset: Int = 0, length: Int = data.size): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(data, offset, length)
        val digest = md.digest()
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(v.toString(16))
        }
        return sb.toString().lowercase(Locale.ROOT)
    }
}
