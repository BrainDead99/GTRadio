package com.sc.gtradio.media.stations

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource

@UnstableApi
class AdfXorDataSource(
    private val upstream: DataSource,
    private val xorMask: Int
) : DataSource by upstream {

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytesRead = upstream.read(buffer, offset, length)
        if (bytesRead > 0 && xorMask != 0) {
            for (i in offset until (offset + bytesRead)) {
                buffer[i] = (buffer[i].toInt() xor xorMask).toByte()
            }
        }
        return bytesRead
    }

    class Factory(
        private val context: Context,
        private val xorMask: Int = 0x22
    ) : DataSource.Factory {
        private val defaultFactory = DefaultDataSource.Factory(context)
        override fun createDataSource(): DataSource {
            return AdfXorDataSource(defaultFactory.createDataSource(), xorMask)
        }
    }
}
