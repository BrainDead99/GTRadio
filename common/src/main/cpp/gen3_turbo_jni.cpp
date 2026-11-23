#include <jni.h>
#include <cstdint>
#include <vector>
#include <cstring>
#include <algorithm>

static const int IMA_INDEX_TABLE[16] = {
    -1, -1, -1, -1, 2, 4, 6, 8,
    -1, -1, -1, -1, 2, 4, 6, 8
};

static const int IMA_STEP_TABLE[89] = {
    7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
    19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
    50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
    130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
    337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
    876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
    2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
    5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
    15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
};

static inline int clipInt(int value, int minVal, int maxVal) {
    if (value < minVal) return minVal;
    if (value > maxVal) return maxVal;
    return value;
}

static int decodeRpf7AdpcmChannel(
    const uint8_t *data,
    int offset,
    int length,
    int sampleCount,
    std::vector<int16_t> &outPcm
) {
    int predictor = 0;
    int stepIndex = 0;
    int readingOffset = offset;
    int endOffset = offset + length;
    int samplesLeft = sampleCount;
    int bytesInBlock = 0;
    int samplesDecoded = 0;

    outPcm.clear();
    if (sampleCount > 0) {
        outPcm.reserve(sampleCount);
    }

    while (readingOffset < endOffset && samplesLeft > 0) {
        if (bytesInBlock == 0) {
            if (readingOffset + 4 > endOffset) break;
            stepIndex = clipInt(static_cast<int>(data[readingOffset]), 0, 88);
            uint8_t p0 = data[readingOffset + 2];
            int8_t p1 = static_cast<int8_t>(data[readingOffset + 3]);
            int rawPred = static_cast<int>(p0) | (static_cast<int>(p1) << 8);
            predictor = static_cast<int>(static_cast<int16_t>(rawPred));
            bytesInBlock = 2044;
            readingOffset += 4;
        } else {
            uint8_t b = data[readingOffset];

            // Low nibble
            int nibble0 = b & 0x0F;
            int step0 = IMA_STEP_TABLE[stepIndex];
            int diff0 = ((((nibble0 & 7) << 1) + 1) * step0) >> 3;
            if (nibble0 & 8) diff0 = -diff0;
            predictor += diff0;
            stepIndex = clipInt(stepIndex + IMA_INDEX_TABLE[nibble0], 0, 88);
            outPcm.push_back(static_cast<int16_t>(clipInt(predictor, -32768, 32767)));
            samplesDecoded++;
            samplesLeft--;

            // High nibble
            if (samplesLeft > 0) {
                int nibble1 = (b >> 4) & 0x0F;
                int step1 = IMA_STEP_TABLE[stepIndex];
                int diff1 = ((((nibble1 & 7) << 1) + 1) * step1) >> 3;
                if (nibble1 & 8) diff1 = -diff1;
                predictor += diff1;
                stepIndex = clipInt(stepIndex + IMA_INDEX_TABLE[nibble1], 0, 88);
                outPcm.push_back(static_cast<int16_t>(clipInt(predictor, -32768, 32767)));
                samplesDecoded++;
                samplesLeft--;
            }

            bytesInBlock--;
            readingOffset++;
        }
    }
    return samplesDecoded;
}

static inline int16_t decodeRpf3Nibble(int nibble, int &valpred, int &idx) {
    int step = IMA_STEP_TABLE[idx];
    int delta = nibble & 0x0F;

    idx += IMA_INDEX_TABLE[delta];
    if (idx < 0) idx = 0; else if (idx > 88) idx = 88;

    int mag = delta & 7;
    int vpdiff = step >> 3;
    if (mag & 4) vpdiff += step;
    if (mag & 2) vpdiff += step >> 1;
    if (mag & 1) vpdiff += step >> 2;

    if (delta & 8) valpred -= vpdiff; else valpred += vpdiff;
    if (valpred > 32767) valpred = 32767; else if (valpred < -32768) valpred = -32768;

    return static_cast<int16_t>(valpred);
}

extern "C" JNIEXPORT void JNICALL
Java_com_sc_gtradio_media_core_Gen3NativeTurbo_nativeDecryptAwcXxtea(
        JNIEnv *env,
        jclass /* clazz */,
        jbyteArray data,
        jint offset,
        jint length,
        jintArray key) {
    if (!data || !key || length <= 0 || offset < 0) return;

    jsize dataLen = env->GetArrayLength(data);
    if (offset + length > dataLen) return;

    int blockCount = length / 4;
    if (blockCount <= 0) return;

    jsize keyLen = env->GetArrayLength(key);
    if (keyLen < 4) return;

    jbyte *dataBytes = env->GetByteArrayElements(data, nullptr);
    if (!dataBytes) return;

    jint *keyWords = env->GetIntArrayElements(key, nullptr);
    if (!keyWords) {
        env->ReleaseByteArrayElements(data, dataBytes, JNI_ABORT);
        return;
    }

    const uint32_t *awcKey = reinterpret_cast<const uint32_t *>(keyWords);
    uint8_t *raw = reinterpret_cast<uint8_t *>(dataBytes);

    std::vector<uint32_t> blocks(blockCount);
    for (int k = 0; k < blockCount; ++k) {
        int o = offset + k * 4;
        blocks[k] = static_cast<uint32_t>(raw[o]) |
                    (static_cast<uint32_t>(raw[o + 1]) << 8) |
                    (static_cast<uint32_t>(raw[o + 2]) << 16) |
                    (static_cast<uint32_t>(raw[o + 3]) << 24);
    }

    uint32_t b = blocks[0];
    const uint32_t delta = 0x9E3779B9u;
    uint32_t sum = delta * static_cast<uint32_t>(6 + 52 / blockCount);

    while (sum != 0) {
        for (int blockIdx = blockCount - 1; blockIdx >= 0; --blockIdx) {
            uint32_t a = (blockIdx > 0) ? blocks[blockIdx - 1] : blocks[blockCount - 1];
            uint32_t kIdx = static_cast<uint32_t>(blockIdx & 3) ^ ((sum >> 2) & 3);
            uint32_t mx = (((a >> 5) ^ (b << 2)) + ((b >> 3) ^ (a << 4))) ^
                          ((sum ^ b) + (awcKey[kIdx] ^ a ^ 0x7B3A207Fu));
            b = blocks[blockIdx] - mx;
            blocks[blockIdx] = b;
        }
        sum -= delta;
    }

    for (int k = 0; k < blockCount; ++k) {
        int o = offset + k * 4;
        uint32_t v = blocks[k];
        raw[o]     = static_cast<uint8_t>(v & 0xFF);
        raw[o + 1] = static_cast<uint8_t>((v >> 8) & 0xFF);
        raw[o + 2] = static_cast<uint8_t>((v >> 16) & 0xFF);
        raw[o + 3] = static_cast<uint8_t>((v >> 24) & 0xFF);
    }

    env->ReleaseIntArrayElements(key, keyWords, JNI_ABORT);
    env->ReleaseByteArrayElements(data, dataBytes, 0);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sc_gtradio_media_core_Gen3NativeTurbo_nativeDecodeImaAdpcmToPcm16(
        JNIEnv *env,
        jclass /* clazz */,
        jbyteArray leftData,
        jint leftOffset,
        jint leftLength,
        jint leftSampleCount,
        jbyteArray rightData,
        jint rightOffset,
        jint rightLength,
        jint rightSampleCount) {
    if (!leftData || leftLength <= 0 || leftSampleCount <= 0 || leftOffset < 0) {
        return env->NewByteArray(0);
    }

    jsize leftDataLen = env->GetArrayLength(leftData);
    if (leftOffset + leftLength > leftDataLen) {
        return env->NewByteArray(0);
    }

    jbyte *leftBytes = env->GetByteArrayElements(leftData, nullptr);
    if (!leftBytes) return env->NewByteArray(0);

    std::vector<int16_t> leftPcm;
    decodeRpf7AdpcmChannel(
        reinterpret_cast<const uint8_t *>(leftBytes),
        leftOffset,
        leftLength,
        leftSampleCount,
        leftPcm
    );
    env->ReleaseByteArrayElements(leftData, leftBytes, JNI_ABORT);

    bool isStereo = (rightData != nullptr && rightLength > 0 && rightSampleCount > 0);
    if (isStereo) {
        jsize rightDataLen = env->GetArrayLength(rightData);
        if (rightOffset >= 0 && rightOffset + rightLength <= rightDataLen) {
            jbyte *rightBytes = env->GetByteArrayElements(rightData, nullptr);
            if (rightBytes) {
                std::vector<int16_t> rightPcm;
                decodeRpf7AdpcmChannel(
                    reinterpret_cast<const uint8_t *>(rightBytes),
                    rightOffset,
                    rightLength,
                    rightSampleCount,
                    rightPcm
                );
                env->ReleaseByteArrayElements(rightData, rightBytes, JNI_ABORT);

                int frames = std::min(static_cast<int>(leftPcm.size()), static_cast<int>(rightPcm.size()));
                jsize outSize = frames * 4;
                jbyteArray result = env->NewByteArray(outSize);
                if (result && outSize > 0) {
                    jbyte *dst = env->GetByteArrayElements(result, nullptr);
                    if (dst) {
                        for (int i = 0; i < frames; ++i) {
                            int16_t l = leftPcm[i];
                            int16_t r = rightPcm[i];
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

    // Mono
    int frames = static_cast<int>(leftPcm.size());
    jsize outSize = frames * 2;
    jbyteArray result = env->NewByteArray(outSize);
    if (result && outSize > 0) {
        jbyte *dst = env->GetByteArrayElements(result, nullptr);
        if (dst) {
            for (int i = 0; i < frames; ++i) {
                int16_t l = leftPcm[i];
                dst[i * 2 + 0] = static_cast<jbyte>(l & 0xFF);
                dst[i * 2 + 1] = static_cast<jbyte>((l >> 8) & 0xFF);
            }
            env->ReleaseByteArrayElements(result, dst, 0);
        }
    }
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sc_gtradio_media_core_Gen3NativeTurbo_nativeDecodeRpf3ImaAdpcmToPcm16(
        JNIEnv *env,
        jclass /* clazz */,
        jbyteArray blockBytes,
        jint ch0Offset,
        jint ch1Offset,
        jint numBytesPerCh,
        jint sampleCount,
        jint channels,
        jintArray state) {
    if (!blockBytes || !state || numBytesPerCh <= 0 || sampleCount <= 0 || channels <= 0) {
        return env->NewByteArray(0);
    }

    jsize blockLen = env->GetArrayLength(blockBytes);
    if (ch0Offset < 0 || ch0Offset + numBytesPerCh > blockLen) {
        return env->NewByteArray(0);
    }
    if (channels == 2 && (ch1Offset < 0 || ch1Offset + numBytesPerCh > blockLen)) {
        return env->NewByteArray(0);
    }

    jsize stateLen = env->GetArrayLength(state);
    if (stateLen < 4) {
        return env->NewByteArray(0);
    }

    jbyte *bytes = env->GetByteArrayElements(blockBytes, nullptr);
    if (!bytes) return env->NewByteArray(0);

    jint *stateElems = env->GetIntArrayElements(state, nullptr);
    if (!stateElems) {
        env->ReleaseByteArrayElements(blockBytes, bytes, JNI_ABORT);
        return env->NewByteArray(0);
    }

    int leftVal = stateElems[0];
    int leftIdx = stateElems[1];
    int rightVal = stateElems[2];
    int rightIdx = stateElems[3];

    const uint8_t *raw = reinterpret_cast<const uint8_t *>(bytes);
    jsize outSize = sampleCount * channels * 2;
    jbyteArray result = env->NewByteArray(outSize);
    if (!result) {
        env->ReleaseIntArrayElements(state, stateElems, JNI_ABORT);
        env->ReleaseByteArrayElements(blockBytes, bytes, JNI_ABORT);
        return env->NewByteArray(0);
    }

    jbyte *dst = env->GetByteArrayElements(result, nullptr);
    if (dst) {
        int decodedInBlk = 0;
        int outPos = 0;
        for (int i = 0; i < numBytesPerCh; ++i) {
            uint8_t bL = raw[ch0Offset + i];
            uint8_t bR = (channels == 2) ? raw[ch1Offset + i] : 0;

            if (decodedInBlk < sampleCount) {
                int16_t sL1 = decodeRpf3Nibble(bL & 0x0F, leftVal, leftIdx);
                dst[outPos++] = static_cast<jbyte>(sL1 & 0xFF);
                dst[outPos++] = static_cast<jbyte>((sL1 >> 8) & 0xFF);
                if (channels == 2) {
                    int16_t sR1 = decodeRpf3Nibble(bR & 0x0F, rightVal, rightIdx);
                    dst[outPos++] = static_cast<jbyte>(sR1 & 0xFF);
                    dst[outPos++] = static_cast<jbyte>((sR1 >> 8) & 0xFF);
                }
                decodedInBlk++;
            }

            if (decodedInBlk < sampleCount) {
                int16_t sL2 = decodeRpf3Nibble((bL >> 4) & 0x0F, leftVal, leftIdx);
                dst[outPos++] = static_cast<jbyte>(sL2 & 0xFF);
                dst[outPos++] = static_cast<jbyte>((sL2 >> 8) & 0xFF);
                if (channels == 2) {
                    int16_t sR2 = decodeRpf3Nibble((bR >> 4) & 0x0F, rightVal, rightIdx);
                    dst[outPos++] = static_cast<jbyte>(sR2 & 0xFF);
                    dst[outPos++] = static_cast<jbyte>((sR2 >> 8) & 0xFF);
                }
                decodedInBlk++;
            }
        }
        env->ReleaseByteArrayElements(result, dst, 0);
    }

    stateElems[0] = leftVal;
    stateElems[1] = leftIdx;
    stateElems[2] = rightVal;
    stateElems[3] = rightIdx;
    env->ReleaseIntArrayElements(state, stateElems, 0);
    env->ReleaseByteArrayElements(blockBytes, bytes, JNI_ABORT);

    return result;
}
