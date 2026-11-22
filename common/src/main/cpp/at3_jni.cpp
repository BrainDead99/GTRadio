#include <jni.h>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <cmath>
#include <vector>
#include "at3_decoders.h"
#include "compat.h"

static void writeWavHeader(FILE *fp, uint32_t pcmDataBytes, uint32_t sampleRate, uint16_t channels) {
    uint32_t byteRate = sampleRate * channels * 2;
    uint16_t blockAlign = channels * 2;
    uint16_t bitsPerSample = 16;
    uint32_t riffSize = 36 + pcmDataBytes;
    uint32_t fmtSize = 16;
    uint16_t audioFormat = 1; // PCM

    fwrite("RIFF", 1, 4, fp);
    fwrite(&riffSize, 4, 1, fp);
    fwrite("WAVE", 1, 4, fp);
    fwrite("fmt ", 1, 4, fp);
    fwrite(&fmtSize, 4, 1, fp);
    fwrite(&audioFormat, 2, 1, fp);
    fwrite(&channels, 2, 1, fp);
    fwrite(&sampleRate, 4, 1, fp);
    fwrite(&byteRate, 4, 1, fp);
    fwrite(&blockAlign, 2, 1, fp);
    fwrite(&bitsPerSample, 2, 1, fp);
    fwrite("data", 1, 4, fp);
    fwrite(&pcmDataBytes, 4, 1, fp);
}

static inline int16_t floatToPcm16(float s, float scale) {
    float v = s * scale;
    if (v > 32767.0f) return 32767;
    if (v < -32768.0f) return -32768;
    return static_cast<int16_t>(lrintf(v));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sc_gtradio_media_gen1_At3AudioArchive_nativeDecodeAt3ToWav(
        JNIEnv *env,
        jclass /* clazz */,
        jbyteArray inDataArr,
        jint inLen,
        jint channels,
        jint sampleRate,
        jint blockAlign,
        jboolean isAtrac3Plus,
        jbyteArray extraDataArr,
        jint skipFrames,
        jstring outWavPathStr) {

    if (inDataArr == nullptr || outWavPathStr == nullptr || blockAlign <= 0 || inLen < blockAlign) {
        return JNI_FALSE;
    }

    int outChannels = (channels >= 2) ? 2 : 1;
    int align = blockAlign;

    ATRAC3PContext *ctxP = nullptr;
    ATRAC3Context *ctx3 = nullptr;

    if (isAtrac3Plus) {
        ctxP = atrac3p_alloc(outChannels, &align);
        if (!ctxP) return JNI_FALSE;
    } else {
        std::vector<uint8_t> extraVec;
        if (extraDataArr != nullptr) {
            jsize elen = env->GetArrayLength(extraDataArr);
            if (elen > 0) {
                extraVec.resize(elen);
                env->GetByteArrayRegion(extraDataArr, 0, elen, reinterpret_cast<jbyte *>(extraVec.data()));
            }
        }
        ctx3 = atrac3_alloc(outChannels, &align, extraVec.empty() ? nullptr : extraVec.data(), static_cast<int>(extraVec.size()));
        if (!ctx3) return JNI_FALSE;
    }

    const char *outPath = env->GetStringUTFChars(outWavPathStr, nullptr);
    FILE *fp = fopen(outPath, "wb");
    env->ReleaseStringUTFChars(outWavPathStr, outPath);

    if (!fp) {
        if (ctxP) atrac3p_free(ctxP);
        if (ctx3) atrac3_free(ctx3);
        return JNI_FALSE;
    }

    // Reserve 44-byte WAV header
    writeWavHeader(fp, 0, static_cast<uint32_t>(sampleRate), static_cast<uint16_t>(outChannels));

    jbyte *inBytes = env->GetByteArrayElements(inDataArr, nullptr);

    std::vector<float> ch0(4096, 0.0f);
    std::vector<float> ch1(4096, 0.0f);
    float *outPtrs[2] = { ch0.data(), ch1.data() };

    std::vector<uint8_t> paddedFrame(static_cast<size_t>(blockAlign) + AV_INPUT_BUFFER_PADDING_SIZE, 0);
    std::vector<int16_t> pcmBuf(4096 * 2);

    uint32_t totalPcmBytes = 0;
    int totalFrames = inLen / blockAlign;
    float scale = 32767.0f;
    bool scaleChecked = false;

    for (int f = 0; f < totalFrames; f++) {
        const uint8_t *src = reinterpret_cast<const uint8_t *>(inBytes) + (f * blockAlign);
        std::memcpy(paddedFrame.data(), src, blockAlign);
        std::memset(paddedFrame.data() + blockAlign, 0, AV_INPUT_BUFFER_PADDING_SIZE);

        int nbSamples = 0;
        int ret = 0;
        if (ctxP) {
            ret = atrac3p_decode_frame(ctxP, outPtrs, &nbSamples, paddedFrame.data(), blockAlign);
        } else {
            ret = atrac3_decode_frame(ctx3, outPtrs, &nbSamples, paddedFrame.data(), blockAlign);
        }

        if (f < skipFrames) {
            continue;
        }

        if (ret < 0 || nbSamples <= 0) {
            nbSamples = isAtrac3Plus ? 2048 : 1024;
            std::fill(ch0.begin(), ch0.begin() + nbSamples, 0.0f);
            std::fill(ch1.begin(), ch1.begin() + nbSamples, 0.0f);
        } else if (!scaleChecked) {
            float maxAbs = 0.0f;
            for (int i = 0; i < nbSamples; i++) {
                float a = std::fabs(ch0[i]);
                if (a > maxAbs) maxAbs = a;
            }
            if (maxAbs > 2.0f) {
                scale = 1.0f;
            } else if (maxAbs > 0.0001f) {
                scale = 32767.0f;
                scaleChecked = true;
            }
        }

        if (outChannels == 2) {
            for (int i = 0; i < nbSamples; i++) {
                pcmBuf[i * 2]     = floatToPcm16(ch0[i], scale);
                pcmBuf[i * 2 + 1] = floatToPcm16(ch1[i], scale);
            }
            size_t bytesToWrite = static_cast<size_t>(nbSamples) * 4;
            fwrite(pcmBuf.data(), 1, bytesToWrite, fp);
            totalPcmBytes += static_cast<uint32_t>(bytesToWrite);
        } else {
            for (int i = 0; i < nbSamples; i++) {
                pcmBuf[i] = floatToPcm16(ch0[i], scale);
            }
            size_t bytesToWrite = static_cast<size_t>(nbSamples) * 2;
            fwrite(pcmBuf.data(), 1, bytesToWrite, fp);
            totalPcmBytes += static_cast<uint32_t>(bytesToWrite);
        }
    }

    env->ReleaseByteArrayElements(inDataArr, inBytes, JNI_ABORT);

    fseek(fp, 0, SEEK_SET);
    writeWavHeader(fp, totalPcmBytes, static_cast<uint32_t>(sampleRate), static_cast<uint16_t>(outChannels));
    fclose(fp);

    if (ctxP) atrac3p_free(ctxP);
    if (ctx3) atrac3_free(ctx3);

    return (totalPcmBytes > 0) ? JNI_TRUE : JNI_FALSE;
}
