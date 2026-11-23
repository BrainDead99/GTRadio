package com.sc.gtradio.media.rpf7

import com.sc.gtradio.common.GTRadioLogger

/**
 * High-performance native MP3 decoder using CC0 public-domain minimp3.
 * Decodes single frames or streams of MPEG-1/2/2.5 Layer III audio directly to 16-bit PCM.
 */
object NativeMp3Decoder {

    init {
        try {
            System.loadLibrary("gtradio_at3")
        } catch (t: Throwable) {
            GTRadioLogger.log("NativeMp3Decoder: Failed to load libgtradio_at3.so", Exception(t))
        }
    }

    @JvmStatic
    external fun nativeDecodeMonoMp3(mp3Data: ByteArray): ShortArray

    @JvmStatic
    external fun nativeDecodeMonoMp3WithWarmup(mp3Data: ByteArray, warmupData: ByteArray?): ShortArray

    @JvmStatic
    external fun nativeDecodeDualMonoMp3BlockToPcmBytes(
        leftMp3: ByteArray?,
        leftWarmup: ByteArray?,
        leftSkip: Int,
        leftCount: Int,
        rightMp3: ByteArray?,
        rightWarmup: ByteArray?,
        rightSkip: Int,
        rightCount: Int
    ): ByteArray
}
