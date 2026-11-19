package com.sc.gtradio.media.stations

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.sc.gtradio.common.GTRadioLogger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.Locale

/**
 * Thread-safe singleton managing Gen3 RPF3 32-byte AES key privately inside internal storage.
 *
 * STRICT LEGAL NOTICE:
 * This file NEVER contains, hardcodes, or bundles any raw proprietary key bytes.
 * Only the public SHA-1 verification hash is stored.
 */
object Rpf3KeyManager {

    // Public verification SHA-1 hash (never store raw key bytes in code!)
    const val RPF3_AES_KEY_SHA1 = "DEA375EF1E6EF2223A1221C2C575C47BF17EFA5E"

    // Known PE raw offsets for PC builds of GTAIV.exe / EFLC.exe
    private val KNOWN_OFFSETS = longArrayOf(
        0x00C5B73CL, // Steam Complete Edition / Gen3
        0x00A95504L, // Classic PC 1.0.8.0
        0x00B43004L, // Classic PC 1.0.7.0
        0x00B44004L, // Classic PC 1.0.6.0
        0x00B4A004L, // Classic PC 1.0.4.0
        0x00BA0004L, // Classic PC 1.0.3.0
        0x00C4F004L  // EFLC 1.1.2.0
    )

    sealed class ImportResult {
        data class Success(val source: String) : ImportResult()
        data class Error(val message: String, val cause: Throwable? = null) : ImportResult()
        object NoKeysFound : ImportResult()
    }

    @Volatile
    private var cachedKey: ByteArray? = null

    /**
     * Checks if a valid GTA IV/EFLC key is currently loaded or available in private storage.
     */
    fun isUnlocked(context: Context): Boolean {
        if (cachedKey != null) return true
        return getKey(context) != null
    }

    /**
     * Retrieves the 32-byte RPF3 AES key, attempting to load from private storage if not in memory.
     * Automatically migrates from legacy gta4_keys if present.
     */
    @Synchronized
    fun getKey(context: Context): ByteArray? {
        cachedKey?.let { return it }

        val newDir = File(context.filesDir, "rpf3_keys")
        val keyFile = File(newDir, "rpf3_aes_key.dat")

        if (!keyFile.exists()) {
            val legacyFile = File(context.filesDir, "gta4_keys/gta4_aes_key.dat")
            if (legacyFile.exists() && legacyFile.length() == 32L) {
                try {
                    if (!newDir.exists()) newDir.mkdirs()
                    legacyFile.copyTo(keyFile, overwrite = true)
                    GTRadioLogger.log("Rpf3KeyManager: Migrated key from legacy gta4_keys to rpf3_keys")
                } catch (e: Exception) {
                    GTRadioLogger.log("Rpf3KeyManager: Failed migrating legacy key file", e)
                }
            }
        }

        if (!keyFile.exists() || keyFile.length() != 32L) return null

        try {
            val bytes = keyFile.readBytes()
            if (bytes.size == 32 && sha1Hex(bytes).equals(RPF3_AES_KEY_SHA1, ignoreCase = true)) {
                cachedKey = bytes
                GTRadioLogger.log("Rpf3KeyManager: Successfully loaded key from private storage")
                return bytes
            } else {
                GTRadioLogger.log("Rpf3KeyManager: Private key file SHA-1 check failed")
            }
        } catch (e: Exception) {
            GTRadioLogger.log("Rpf3KeyManager: Error reading private key file", e)
        }
        return null
    }

    /**
     * Ensures the key is loaded if available.
     */
    fun ensureKeyLoaded(context: Context): Boolean = getKey(context) != null

    /**
     * Returns "Unlocked" or "Locked".
     */
    fun getStatusLabel(context: Context): String {
        return if (isUnlocked(context)) "Unlocked" else "Locked"
    }

    /**
     * Clears in-memory key and deletes private storage key file (and legacy key file).
     */
    @Synchronized
    fun clearCachedKey(context: Context): Boolean {
        cachedKey = null
        val keyFile = File(context.filesDir, "rpf3_keys/rpf3_aes_key.dat")
        val legacyKeyFile = File(context.filesDir, "gta4_keys/gta4_aes_key.dat")
        var success = true
        if (keyFile.exists() && !keyFile.delete()) success = false
        if (legacyKeyFile.exists() && !legacyKeyFile.delete()) success = false
        return success
    }

    /**
     * Imports the key from a user-selected folder:
     * 1. Checks for pre-extracted 32-byte .dat key file (gta4_aes_key.dat, gtaiv_aes_key.dat, or rpf3_aes_key.dat).
     * 2. Checks for GTAIV.exe or EFLC.exe and extracts the key using known PC offsets or PE section streaming.
     */
    @Synchronized
    fun importFromFolder(context: Context, folderDoc: DocumentFile): ImportResult {
        GTRadioLogger.log("Rpf3KeyManager: Scanning folder for keys: ${folderDoc.name} (${folderDoc.uri})")

        // 1. Try pre-extracted .dat file
        val datResult = tryImportDatFile(context, folderDoc)
        if (datResult is ImportResult.Success) {
            return datResult
        }

        // 2. Try GTAIV.exe or EFLC.exe
        val exeResult = tryImportFromExe(context, folderDoc)
        if (exeResult is ImportResult.Success) {
            return exeResult
        }

        return if (datResult is ImportResult.Error) {
            datResult
        } else if (exeResult is ImportResult.Error) {
            exeResult
        } else {
            ImportResult.NoKeysFound
        }
    }

    private fun tryImportDatFile(context: Context, folderDoc: DocumentFile): ImportResult {
        val files = try { folderDoc.listFiles() } catch (e: Exception) { emptyArray() }
        val candidateNames = setOf("gta4_aes_key.dat", "gtaiv_aes_key.dat", "rpf3_aes_key.dat")
        val targetDoc = files.find { f ->
            f.name?.lowercase(Locale.ROOT) in candidateNames
        } ?: return ImportResult.NoKeysFound

        return try {
            val bytes = context.contentResolver.openInputStream(targetDoc.uri)?.use { it.readBytes() }
                ?: return ImportResult.Error("Could not open ${targetDoc.name}")
            if (bytes.size != 32) {
                return ImportResult.Error("${targetDoc.name} is not 32 bytes (size=${bytes.size})")
            }
            if (!sha1Hex(bytes).equals(RPF3_AES_KEY_SHA1, ignoreCase = true)) {
                return ImportResult.Error("${targetDoc.name} SHA-1 checksum does not match RPF3 AES key")
            }
            saveKeyPrivately(context, bytes)
            cachedKey = bytes
            ImportResult.Success("Imported from ${targetDoc.name}")
        } catch (e: Exception) {
            ImportResult.Error("Failed importing ${targetDoc.name}: ${e.message}", e)
        }
    }

    private fun tryImportFromExe(context: Context, folderDoc: DocumentFile): ImportResult {
        val files = try { folderDoc.listFiles() } catch (e: Exception) { emptyArray() }
        val exeDoc = files.find { f ->
            val n = f.name?.lowercase(Locale.ROOT) ?: ""
            n == "gtaiv.exe" || n == "eflc.exe"
        } ?: return ImportResult.NoKeysFound

        return try {
            val keyBytes = extractKeyFromExe(context, exeDoc)
            if (keyBytes != null) {
                saveKeyPrivately(context, keyBytes)
                cachedKey = keyBytes
                ImportResult.Success("Extracted from ${exeDoc.name}")
            } else {
                ImportResult.Error("RPF3 AES key not found in ${exeDoc.name}")
            }
        } catch (e: Exception) {
            ImportResult.Error("Failed scanning ${exeDoc.name}: ${e.message}", e)
        }
    }

    private fun extractKeyFromExe(context: Context, exeDoc: DocumentFile): ByteArray? {
        context.contentResolver.openFileDescriptor(exeDoc.uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                val size = channel.size()

                // Fast path: known offsets
                val candidateBuf = ByteBuffer.allocate(32)
                for (offset in KNOWN_OFFSETS) {
                    if (offset + 32L <= size) {
                        candidateBuf.clear()
                        channel.position(offset)
                        channel.read(candidateBuf)
                        val candidate = candidateBuf.array()
                        if (sha1Hex(candidate).equals(RPF3_AES_KEY_SHA1, ignoreCase = true)) {
                            GTRadioLogger.log("Rpf3KeyManager: Key found at fast-path offset 0x${offset.toString(16).uppercase(Locale.ROOT)}")
                            return candidate
                        }
                    }
                }

                // Fallback: PE section table streaming
                GTRadioLogger.log("Rpf3KeyManager: Fast-path miss; parsing PE sections...")
                val sections = parsePeSections(channel)
                // Scan .data first, then .rdata
                val orderedSections = sections.sortedBy { sec ->
                    val lower = sec.name.lowercase(Locale.ROOT)
                    if (lower.startsWith(".data")) 0 else if (lower.startsWith(".rdata") || lower.startsWith("_rdata")) 1 else 2
                }

                for (sec in orderedSections) {
                    val lower = sec.name.lowercase(Locale.ROOT)
                    if (lower.startsWith(".data") || lower.startsWith(".rdata") || lower.startsWith("_rdata")) {
                        val found = scanSectionForKey(channel, sec)
                        if (found != null) return found
                    }
                }
            }
        }
        return null
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

        // Read PE header (Signature + COFF Header = 24 bytes)
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

    private fun scanSectionForKey(channel: FileChannel, section: PeSection): ByteArray? {
        val chunkSize = 1024 * 1024 // <= 1 MB RAM
        val overlap = 64
        val buf = ByteBuffer.allocate(chunkSize)
        val rawBytes = ByteArray(chunkSize)

        // 1st pass: 4-byte aligned offsets
        var filePos = section.rawPtr
        val endPos = section.rawPtr + section.rawSize

        while (filePos < endPos) {
            buf.clear()
            val toRead = Math.min(chunkSize.toLong(), endPos - filePos).toInt()
            buf.limit(toRead)
            channel.position(filePos)
            val bytesRead = channel.read(buf)
            if (bytesRead < 32) break

            buf.flip()
            buf.get(rawBytes, 0, bytesRead)

            val maxOffset = bytesRead - 32
            var off = 0
            while (off <= maxOffset) {
                if (sha1Hex(rawBytes, off, 32).equals(RPF3_AES_KEY_SHA1, ignoreCase = true)) {
                    val key = rawBytes.copyOfRange(off, off + 32)
                    GTRadioLogger.log("Rpf3KeyManager: Key found in ${section.name} at 0x${(filePos + off).toString(16).uppercase(Locale.ROOT)} (4-byte aligned)")
                    return key
                }
                off += 4
            }

            if (bytesRead < chunkSize) break
            filePos += (bytesRead - overlap)
        }

        // 2nd pass: 1-byte fallback if 4-byte aligned didn't find it
        filePos = section.rawPtr
        while (filePos < endPos) {
            buf.clear()
            val toRead = Math.min(chunkSize.toLong(), endPos - filePos).toInt()
            buf.limit(toRead)
            channel.position(filePos)
            val bytesRead = channel.read(buf)
            if (bytesRead < 32) break

            buf.flip()
            buf.get(rawBytes, 0, bytesRead)

            val maxOffset = bytesRead - 32
            for (off in 0..maxOffset) {
                if (off % 4 != 0) { // Skip already tested 4-byte offsets
                    if (sha1Hex(rawBytes, off, 32).equals(RPF3_AES_KEY_SHA1, ignoreCase = true)) {
                        val key = rawBytes.copyOfRange(off, off + 32)
                        GTRadioLogger.log("Rpf3KeyManager: Key found in ${section.name} at 0x${(filePos + off).toString(16).uppercase(Locale.ROOT)} (1-byte fallback)")
                        return key
                    }
                }
            }

            if (bytesRead < chunkSize) break
            filePos += (bytesRead - overlap)
        }

        return null
    }

    private fun saveKeyPrivately(context: Context, keyBytes: ByteArray) {
        val keysDir = File(context.filesDir, "rpf3_keys")
        if (!keysDir.exists()) keysDir.mkdirs()
        val keyFile = File(keysDir, "rpf3_aes_key.dat")
        FileOutputStream(keyFile).use { it.write(keyBytes) }
        GTRadioLogger.log("Rpf3KeyManager: Saved verified key to ${keyFile.absolutePath}")
    }

    private fun sha1Hex(data: ByteArray, offset: Int = 0, length: Int = data.size): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(data, offset, length)
        val digest = md.digest()
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format(Locale.ROOT, "%02X", b))
        }
        return sb.toString()
    }
}
