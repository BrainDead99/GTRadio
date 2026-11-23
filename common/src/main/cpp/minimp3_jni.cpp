#include <jni.h>
#include <cstdint>
#include <vector>

#define MINIMP3_IMPLEMENTATION
#include "minimp3.h"

extern "C" JNIEXPORT jshortArray JNICALL
Java_com_sc_gtradio_media_rpf7_NativeMp3Decoder_nativeDecodeMonoMp3WithWarmup(
        JNIEnv *env,
        jclass /* clazz */,
        jbyteArray mp3Data,
        jbyteArray warmupData) {
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

    // 1. Prime the bit reservoir and MDCT overlap buffer with warmup data if provided
    if (warmupData) {
        jsize warmupLen = env->GetArrayLength(warmupData);
        if (warmupLen > 0) {
            jbyte *wBytes = env->GetByteArrayElements(warmupData, nullptr);
            if (wBytes) {
                const uint8_t *wPtr = reinterpret_cast<const uint8_t *>(wBytes);
                int wRem = warmupLen;
                while (wRem > 0) {
                    mp3dec_decode_frame(&mp3d, wPtr, wRem, pcmFrame, &info);
                    if (info.frame_bytes <= 0) {
                        break;
                    }
                    wPtr += info.frame_bytes;
                    wRem -= info.frame_bytes;
                }
                env->ReleaseByteArrayElements(warmupData, wBytes, JNI_ABORT);
            }
        }
    }

    // 2. Decode the actual target mp3Data
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

extern "C" JNIEXPORT jshortArray JNICALL
Java_com_sc_gtradio_media_rpf7_NativeMp3Decoder_nativeDecodeMonoMp3(
        JNIEnv *env,
        jclass clazz,
        jbyteArray mp3Data) {
    return Java_com_sc_gtradio_media_rpf7_NativeMp3Decoder_nativeDecodeMonoMp3WithWarmup(env, clazz, mp3Data, nullptr);
}

static void decodeMp3ChannelStreamWithWarmup(
        mp3dec_t *mp3d,
        const uint8_t *warmupBytes,
        int warmupLen,
        const uint8_t *mp3Bytes,
        int mp3Len,
        std::vector<int16_t> &outSamples) {
    mp3d_sample_t pcmFrame[MINIMP3_MAX_SAMPLES_PER_FRAME];
    mp3dec_frame_info_t info;

    if (warmupBytes && warmupLen > 0) {
        const uint8_t *wPtr = warmupBytes;
        int wRem = warmupLen;
        while (wRem > 0) {
            mp3dec_decode_frame(mp3d, wPtr, wRem, pcmFrame, &info);
            if (info.frame_bytes <= 0) break;
            wPtr += info.frame_bytes;
            wRem -= info.frame_bytes;
        }
    }

    if (mp3Bytes && mp3Len > 0) {
        const uint8_t *ptr = mp3Bytes;
        int remaining = mp3Len;
        outSamples.reserve((mp3Len / 400 + 1) * 1152);

        while (remaining > 0) {
            int samples = mp3dec_decode_frame(mp3d, ptr, remaining, pcmFrame, &info);
            if (info.frame_bytes <= 0) break;

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
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sc_gtradio_media_rpf7_NativeMp3Decoder_nativeDecodeDualMonoMp3BlockToPcmBytes(
        JNIEnv *env,
        jclass /* clazz */,
        jbyteArray leftMp3,
        jbyteArray leftWarmup,
        jint leftSkip,
        jint leftCount,
        jbyteArray rightMp3,
        jbyteArray rightWarmup,
        jint rightSkip,
        jint rightCount) {
    if (!leftMp3) {
        return env->NewByteArray(0);
    }

    jsize leftLen = env->GetArrayLength(leftMp3);
    if (leftLen <= 0) {
        return env->NewByteArray(0);
    }

    jbyte *leftBytes = env->GetByteArrayElements(leftMp3, nullptr);
    if (!leftBytes) return env->NewByteArray(0);

    jbyte *leftWBytes = nullptr;
    jsize leftWLen = 0;
    if (leftWarmup) {
        leftWLen = env->GetArrayLength(leftWarmup);
        if (leftWLen > 0) {
            leftWBytes = env->GetByteArrayElements(leftWarmup, nullptr);
        }
    }

    mp3dec_t mp3dLeft;
    mp3dec_init(&mp3dLeft);
    std::vector<int16_t> leftDecoded;
    decodeMp3ChannelStreamWithWarmup(
        &mp3dLeft,
        reinterpret_cast<const uint8_t *>(leftWBytes),
        leftWLen,
        reinterpret_cast<const uint8_t *>(leftBytes),
        leftLen,
        leftDecoded
    );

    if (leftWBytes) {
        env->ReleaseByteArrayElements(leftWarmup, leftWBytes, JNI_ABORT);
    }
    env->ReleaseByteArrayElements(leftMp3, leftBytes, JNI_ABORT);

    int leftTotal = static_cast<int>(leftDecoded.size());
    int leftTrimStart = std::max(0, std::min(static_cast<int>(leftSkip), leftTotal));
    int leftTargetEnd = leftTrimStart + leftCount;
    int leftTrimEnd = (leftCount > 0) ? std::min(leftTargetEnd, leftTotal) : leftTotal;
    const int16_t *leftPtr = leftDecoded.data();
    int leftOutLen = leftTotal;
    if (leftTrimStart < leftTrimEnd) {
        leftPtr = leftDecoded.data() + leftTrimStart;
        leftOutLen = leftTrimEnd - leftTrimStart;
    }

    bool isStereo = (rightMp3 != nullptr);
    if (isStereo) {
        jsize rightLen = env->GetArrayLength(rightMp3);
        if (rightLen > 0) {
            jbyte *rightBytes = env->GetByteArrayElements(rightMp3, nullptr);
            if (rightBytes) {
                jbyte *rightWBytes = nullptr;
                jsize rightWLen = 0;
                if (rightWarmup) {
                    rightWLen = env->GetArrayLength(rightWarmup);
                    if (rightWLen > 0) {
                        rightWBytes = env->GetByteArrayElements(rightWarmup, nullptr);
                    }
                }

                mp3dec_t mp3dRight;
                mp3dec_init(&mp3dRight);
                std::vector<int16_t> rightDecoded;
                decodeMp3ChannelStreamWithWarmup(
                    &mp3dRight,
                    reinterpret_cast<const uint8_t *>(rightWBytes),
                    rightWLen,
                    reinterpret_cast<const uint8_t *>(rightBytes),
                    rightLen,
                    rightDecoded
                );

                if (rightWBytes) {
                    env->ReleaseByteArrayElements(rightWarmup, rightWBytes, JNI_ABORT);
                }
                env->ReleaseByteArrayElements(rightMp3, rightBytes, JNI_ABORT);

                int rightTotal = static_cast<int>(rightDecoded.size());
                int rightTrimStart = std::max(0, std::min(static_cast<int>(rightSkip), rightTotal));
                int rightTargetEnd = rightTrimStart + rightCount;
                int rightTrimEnd = (rightCount > 0) ? std::min(rightTargetEnd, rightTotal) : rightTotal;
                const int16_t *rightPtr = rightDecoded.data();
                int rightOutLen = rightTotal;
                if (rightTrimStart < rightTrimEnd) {
                    rightPtr = rightDecoded.data() + rightTrimStart;
                    rightOutLen = rightTrimEnd - rightTrimStart;
                }

                int numFrames = std::min(leftOutLen, rightOutLen);
                jsize outByteSize = numFrames * 4;
                jbyteArray result = env->NewByteArray(outByteSize);
                if (result && outByteSize > 0) {
                    jbyte *dst = env->GetByteArrayElements(result, nullptr);
                    if (dst) {
                        for (int i = 0; i < numFrames; ++i) {
                            int16_t l = leftPtr[i];
                            int16_t r = rightPtr[i];
                            dst[i * 4 + 0] = static_cast<jbyte>(l & 0xFF);
                            dst[i * 4 + 1] = static_cast<jbyte>((l >> 8) & 0xFF);
                            dst[i * 4 + 2] = static_cast<jbyte>(r & 0xFF);
                            dst[i * 4 + 3] = static_cast<jbyte>((r >> 8) & 0xFF);
                        }
                        env->ReleaseByteArrayElements(result, dst, 0);
                    }
                }
                return result;
            }
        }
    }

    int numFrames = leftOutLen;
    jsize outByteSize = numFrames * 2;
    jbyteArray result = env->NewByteArray(outByteSize);
    if (result && outByteSize > 0) {
        jbyte *dst = env->GetByteArrayElements(result, nullptr);
        if (dst) {
            for (int i = 0; i < numFrames; ++i) {
                int16_t l = leftPtr[i];
                dst[i * 2 + 0] = static_cast<jbyte>(l & 0xFF);
                dst[i * 2 + 1] = static_cast<jbyte>((l >> 8) & 0xFF);
            }
            env->ReleaseByteArrayElements(result, dst, 0);
        }
    }
    return result;
}
