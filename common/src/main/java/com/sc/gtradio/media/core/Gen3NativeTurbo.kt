package com.sc.gtradio.media.core

import com.sc.gtradio.common.GTRadioLogger

/**
 * High-performance NDK acceleration for Gen3 audio pipelines:
 * - In-place AWC XXTEA block decryption
 * - 4-bit IMA ADPCM decoding & stereo interleaving (RPF7 Codec 4 & RPF3)
 */
object Gen3NativeTurbo {

    init {
        try {
            System.loadLibrary("gtradio_at3")
        } catch (t: Throwable) {
            GTRadioLogger.log("Gen3NativeTurbo: Failed to load libgtradio_at3.so", Exception(t))
        }
    }

    @JvmStatic
    external fun nativeDecryptAwcXxtea(data: ByteArray, offset: Int, length: Int, key: IntArray)

    @JvmStatic
    external fun nativeDecodeImaAdpcmToPcm16(
        leftData: ByteArray,
        leftOffset: Int,
        leftLength: Int,
        leftSampleCount: Int,
        rightData: ByteArray?,
        rightOffset: Int,
        rightLength: Int,
        rightSampleCount: Int
    ): ByteArray

    @JvmStatic
    external fun nativeDecodeRpf3ImaAdpcmToPcm16(
        blockBytes: ByteArray,
        ch0Offset: Int,
        ch1Offset: Int,
        numBytesPerCh: Int,
        sampleCount: Int,
        channels: Int,
        state: IntArray
    ): ByteArray
}
