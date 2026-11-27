package com.sc.gtradio.media.core

/**
 * Shared thread-safe decoded PCM block cache for Gen3 zero-disk-cache streaming.
 *
 * Strictly bounded by BOTH [MAX_ENTRIES] (4 blocks) and [MAX_BYTES] (16 MB) so
 * active playback + ±1 neighbor pre-warm never exceeds 16 MB peak heap RAM.
 */
data class CachedStreamBlock(
    val pcmBytes: ByteArray,
    val tailWarmupByChannel: Array<ByteArray?>? = null
)

object Gen3StreamBlockCache {
    const val MAX_ENTRIES = 4
    const val MAX_BYTES = 16L * 1024L * 1024L

    private val lock = Any()
    private val entries = LinkedHashMap<String, CachedStreamBlock>(8, 0.75f, true)
    private var totalBytes: Long = 0L

    fun buildKey(scheme: String, archiveStreamId: String, entryName: String, blockIdx: Int): String {
        return "$scheme|$archiveStreamId|$entryName|$blockIdx"
    }

    fun get(key: String): CachedStreamBlock? {
        synchronized(lock) {
            return entries[key]
        }
    }

    fun put(key: String, block: CachedStreamBlock) {
        val entryBytes = blockByteSize(block)
        synchronized(lock) {
            entries.remove(key)?.let { prev ->
                totalBytes = (totalBytes - blockByteSize(prev)).coerceAtLeast(0L)
            }
            if (entryBytes > MAX_BYTES) {
                return
            }
            entries[key] = block
            totalBytes += entryBytes
            val iter = entries.entries.iterator()
            while ((entries.size > MAX_ENTRIES || totalBytes > MAX_BYTES) && iter.hasNext()) {
                val eldest = iter.next()
                totalBytes = (totalBytes - blockByteSize(eldest.value)).coerceAtLeast(0L)
                iter.remove()
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            totalBytes = 0L
        }
    }

    private fun blockByteSize(block: CachedStreamBlock): Long {
        var total = block.pcmBytes.size.toLong()
        val warmups = block.tailWarmupByChannel
        if (warmups != null) {
            for (arr in warmups) {
                if (arr != null) {
                    total += arr.size.toLong()
                }
            }
        }
        return total
    }
}
