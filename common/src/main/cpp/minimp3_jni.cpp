#include <jni.h>
#include <cstdint>
#include <vector>

#define MINIMP3_IMPLEMENTATION
#include "minimp3.h"

extern "C" JNIEXPORT jshortArray JNICALL
Java_com_sc_gtradio_media_stations_NativeMp3Decoder_nativeDecodeMonoMp3(
        JNIEnv *env,
        jclass /* clazz */,
        jbyteArray mp3Data) {
    if (!mp3Data) {
        return env->NewShortArray(0);
    }

    jsize len = env->GetArrayLength(mp3Data);
    if (len <= 0) {
        return env->NewShortArray(0);
    }

    jbyte *bytes = env->GetByteArrayElements(mp3Data, nullptr);
    if (!bytes) {
        return env->NewShortArray(0);
    }

    mp3dec_t mp3d;
    mp3dec_init(&mp3d);

    mp3d_sample_t pcmFrame[MINIMP3_MAX_SAMPLES_PER_FRAME];
    mp3dec_frame_info_t info;

    const uint8_t *ptr = reinterpret_cast<const uint8_t *>(bytes);
    int remaining = len;

    std::vector<int16_t> outSamples;
    // Pre-reserve roughly 1152 samples per 400 bytes of MP3 (~96-128kbps)
    outSamples.reserve((len / 400 + 1) * 1152);

    while (remaining > 0) {
        int samples = mp3dec_decode_frame(&mp3d, ptr, remaining, pcmFrame, &info);
        if (info.frame_bytes <= 0) {
            break;
        }

        if (samples > 0) {
            if (info.channels == 1) {
                outSamples.insert(outSamples.end(), pcmFrame, pcmFrame + samples);
            } else if (info.channels == 2) {
                for (int i = 0; i < samples; ++i) {
                    int32_t mixed = (static_cast<int32_t>(pcmFrame[i * 2]) +
                                     static_cast<int32_t>(pcmFrame[i * 2 + 1])) / 2;
                    outSamples.push_back(static_cast<int16_t>(mixed));
                }
            }
        }

        ptr += info.frame_bytes;
        remaining -= info.frame_bytes;
    }

    env->ReleaseByteArrayElements(mp3Data, bytes, JNI_ABORT);

    jsize outSize = static_cast<jsize>(outSamples.size());
    jshortArray result = env->NewShortArray(outSize);
    if (result && outSize > 0) {
        env->SetShortArrayRegion(result, 0, outSize, reinterpret_cast<const jshort *>(outSamples.data()));
    }
    return result;
}
