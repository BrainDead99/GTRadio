package com.sc.gtradio.media

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.preference.PreferenceManager
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-performance Media3 [AudioProcessor] for cross-game loudness normalization
 * and optional car stereo FM equalizer.
 *
 * Designed with zero per-buffer allocations and direct 16-bit PCM processing.
 */
class RadioAudioProcessor(context: Context) : BaseAudioProcessor() {

    @Volatile
    var isLoudnessEnabled: Boolean = true

    @Volatile
    var isCarEqEnabled: Boolean = false

    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    // Loudness Normalization DSP state
    // Target RMS ~ -14 LUFS equivalent in normalized [-1.0, 1.0] float scale (~0.20)
    private val targetRms = 0.1995f
    private var smoothedRms = targetRms
    private var currentGain = 1.0f

    // Biquad EQ state (Direct Form I: 3 filters x 2 channels)
    // Filter 1: HPF ~90 Hz, Q=0.707
    private var hpfB0 = 1f; private var hpfB1 = 0f; private var hpfB2 = 0f
    private var hpfA1 = 0f; private var hpfA2 = 0f
    private var hpfX1L = 0f; private var hpfX2L = 0f; private var hpfY1L = 0f; private var hpfY2L = 0f
    private var hpfX1R = 0f; private var hpfX2R = 0f; private var hpfY1R = 0f; private var hpfY2R = 0f

    // Filter 2: Peaking EQ ~1800 Hz, Q=0.8, +2.5 dB
    private var peakB0 = 1f; private var peakB1 = 0f; private var peakB2 = 0f
    private var peakA1 = 0f; private var peakA2 = 0f
    private var peakX1L = 0f; private var peakX2L = 0f; private var peakY1L = 0f; private var peakY2L = 0f
    private var peakX1R = 0f; private var peakX2R = 0f; private var peakY1R = 0f; private var peakY2R = 0f

    // Filter 3: LPF ~11000 Hz, Q=0.707
    private var lpfB0 = 1f; private var lpfB1 = 0f; private var lpfB2 = 0f
    private var lpfA1 = 0f; private var lpfA2 = 0f
    private var lpfX1L = 0f; private var lpfX2L = 0f; private var lpfY1L = 0f; private var lpfY2L = 0f
    private var lpfX1R = 0f; private var lpfX2R = 0f; private var lpfY1R = 0f; private var lpfY2R = 0f

    private var activeSampleRate = 44100

    init {
        updateSettings(prefs)
        computeFilterCoefficients(44100)
    }

    fun updateSettings(preferences: SharedPreferences) {
        isLoudnessEnabled = preferences.getBoolean("loudness_normalization_enabled", true)
        isCarEqEnabled = preferences.getBoolean("car_stereo_eq_enabled", false)
    }

    override fun isActive(): Boolean {
        return (isLoudnessEnabled || isCarEqEnabled) &&
            outputAudioFormat != AudioProcessor.AudioFormat.NOT_SET
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        activeSampleRate = if (inputAudioFormat.sampleRate > 0) inputAudioFormat.sampleRate else 44100
        computeFilterCoefficients(activeSampleRate)
        resetFilterState()
        return inputAudioFormat
    }

    private fun computeFilterCoefficients(sampleRate: Int) {
        val fs = sampleRate.toDouble().coerceAtLeast(8000.0)

        // 1. High-Pass Filter: 90 Hz, Q = 0.707
        val w0Hp = 2.0 * Math.PI * 90.0 / fs
        val cosW0Hp = cos(w0Hp)
        val sinW0Hp = sin(w0Hp)
        val alphaHp = sinW0Hp / (2.0 * 0.707)
        val a0Hp = 1.0 + alphaHp
        hpfB0 = (((1.0 + cosW0Hp) / 2.0) / a0Hp).toFloat()
        hpfB1 = ((-(1.0 + cosW0Hp)) / a0Hp).toFloat()
        hpfB2 = (((1.0 + cosW0Hp) / 2.0) / a0Hp).toFloat()
        hpfA1 = ((-2.0 * cosW0Hp) / a0Hp).toFloat()
        hpfA2 = ((1.0 - alphaHp) / a0Hp).toFloat()

        // 2. Peaking EQ: 1800 Hz, Q = 0.8, Gain = +2.5 dB
        val w0Peak = 2.0 * Math.PI * 1800.0 / fs
        val cosW0Peak = cos(w0Peak)
        val sinW0Peak = sin(w0Peak)
        val alphaPeak = sinW0Peak / (2.0 * 0.8)
        val aPeak = Math.pow(10.0, 2.5 / 40.0)
        val a0Peak = 1.0 + (alphaPeak / aPeak)
        peakB0 = ((1.0 + (alphaPeak * aPeak)) / a0Peak).toFloat()
        peakB1 = ((-2.0 * cosW0Peak) / a0Peak).toFloat()
        peakB2 = ((1.0 - (alphaPeak * aPeak)) / a0Peak).toFloat()
        peakA1 = ((-2.0 * cosW0Peak) / a0Peak).toFloat()
        peakA2 = ((1.0 - (alphaPeak / aPeak)) / a0Peak).toFloat()

        // 3. Low-Pass Filter: 11000 Hz, Q = 0.707 (FM roll-off)
        val fcLp = Math.min(11000.0, fs * 0.45)
        val w0Lp = 2.0 * Math.PI * fcLp / fs
        val cosW0Lp = cos(w0Lp)
        val sinW0Lp = sin(w0Lp)
        val alphaLp = sinW0Lp / (2.0 * 0.707)
        val a0Lp = 1.0 + alphaLp
        lpfB0 = (((1.0 - cosW0Lp) / 2.0) / a0Lp).toFloat()
        lpfB1 = ((1.0 - cosW0Lp) / a0Lp).toFloat()
        lpfB2 = (((1.0 - cosW0Lp) / 2.0) / a0Lp).toFloat()
        lpfA1 = ((-2.0 * cosW0Lp) / a0Lp).toFloat()
        lpfA2 = ((1.0 - alphaLp) / a0Lp).toFloat()
    }

    private fun resetFilterState() {
        hpfX1L = 0f; hpfX2L = 0f; hpfY1L = 0f; hpfY2L = 0f
        hpfX1R = 0f; hpfX2R = 0f; hpfY1R = 0f; hpfY2R = 0f
        peakX1L = 0f; peakX2L = 0f; peakY1L = 0f; peakY2L = 0f
        peakX1R = 0f; peakX2R = 0f; peakY1R = 0f; peakY2R = 0f
        lpfX1L = 0f; lpfX2L = 0f; lpfY1L = 0f; lpfY2L = 0f
        lpfX1R = 0f; lpfX2R = 0f; lpfY1R = 0f; lpfY2R = 0f
        smoothedRms = targetRms
        currentGain = 1.0f
    }

    override fun onReset() {
        resetFilterState()
    }

    override fun onFlush() {
        // Preserved filter and AGC state across gapless chunk seams and MediaItem transitions.
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val outputBuffer = replaceOutputBuffer(remaining)

        val loudnessOn = isLoudnessEnabled
        val carEqOn = isCarEqEnabled

        // Fast path: pass-through if both disabled
        if (!loudnessOn && !carEqOn) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        val channelCount = inputAudioFormat.channelCount
        val samplePairs = remaining / (2 * channelCount)

        // Pass 1: Envelope calculation for loudness normalization
        if (loudnessOn) {
            val startPos = inputBuffer.position()
            var sumSq = 0.0
            var count = 0
            while (inputBuffer.hasRemaining()) {
                val s = inputBuffer.short
                val norm = s / 32768.0
                sumSq += norm * norm
                count++
            }
            inputBuffer.position(startPos)

            if (count > 0) {
                val blockRms = sqrt(sumSq / count).toFloat()
                if (blockRms > 0.003f) { // Above noise gate threshold (-50 dB)
                    val alpha = if (blockRms > smoothedRms) 0.08f else 0.008f
                    smoothedRms += alpha * (blockRms - smoothedRms)
                    val targetGain = (targetRms / (smoothedRms + 0.0001f)).coerceIn(0.25f, 2.82f)
                    currentGain += 0.03f * (targetGain - currentGain)
                }
            }
        } else {
            currentGain = 1.0f
        }

        val gain = currentGain

        // Pass 2: Audio processing & soft-clip limiting
        if (channelCount == 2) {
            for (i in 0 until samplePairs) {
                var sL = inputBuffer.short / 32768.0f
                var sR = inputBuffer.short / 32768.0f

                if (carEqOn) {
                    // Left channel cascade: HPF -> Peak -> LPF
                    val hpfOutL = hpfB0 * sL + hpfB1 * hpfX1L + hpfB2 * hpfX2L - hpfA1 * hpfY1L - hpfA2 * hpfY2L
                    hpfX2L = hpfX1L; hpfX1L = sL; hpfY2L = hpfY1L; hpfY1L = hpfOutL

                    val peakOutL = peakB0 * hpfOutL + peakB1 * peakX1L + peakB2 * peakX2L - peakA1 * peakY1L - peakA2 * peakY2L
                    peakX2L = peakX1L; peakX1L = hpfOutL; peakY2L = peakY1L; peakY1L = peakOutL

                    val lpfOutL = lpfB0 * peakOutL + lpfB1 * lpfX1L + lpfB2 * lpfX2L - lpfA1 * lpfY1L - lpfA2 * lpfY2L
                    lpfX2L = lpfX1L; lpfX1L = peakOutL; lpfY2L = peakY1L; lpfY1L = lpfOutL
                    sL = lpfOutL

                    // Right channel cascade: HPF -> Peak -> LPF
                    val hpfOutR = hpfB0 * sR + hpfB1 * hpfX1R + hpfB2 * hpfX2R - hpfA1 * hpfY1R - hpfA2 * hpfY2R
                    hpfX2R = hpfX1R; hpfX1R = sR; hpfY2R = hpfY1R; hpfY1R = hpfOutR

                    val peakOutR = peakB0 * hpfOutR + peakB1 * peakX1R + peakB2 * peakX2R - peakA1 * peakY1R - peakA2 * peakY2R
                    peakX2R = peakX1R; peakX1R = hpfOutR; peakY2R = peakY1R; peakY1R = peakOutR

                    val lpfOutR = lpfB0 * peakOutR + lpfB1 * lpfX1R + lpfB2 * lpfX2R - lpfA1 * lpfY1R - lpfA2 * lpfY2R
                    lpfX2R = lpfX1R; lpfX1R = peakOutR; lpfY2R = peakY1R; lpfY1R = lpfOutR
                    sR = lpfOutR
                }

                if (loudnessOn) {
                    sL *= gain
                    sR *= gain
                }

                // Soft-clip limiter
                val outL = softClip(sL)
                val outR = softClip(sR)

                outputBuffer.putShort((outL * 32767.0f).toInt().coerceIn(-32768, 32767).toShort())
                outputBuffer.putShort((outR * 32767.0f).toInt().coerceIn(-32768, 32767).toShort())
            }
        } else {
            // Mono / multi-channel fallback
            while (inputBuffer.hasRemaining()) {
                var s = inputBuffer.short / 32768.0f
                if (carEqOn) {
                    val hpfOut = hpfB0 * s + hpfB1 * hpfX1L + hpfB2 * hpfX2L - hpfA1 * hpfY1L - hpfA2 * hpfY2L
                    hpfX2L = hpfX1L; hpfX1L = s; hpfY2L = hpfY1L; hpfY1L = hpfOut

                    val peakOut = peakB0 * hpfOut + peakB1 * peakX1L + peakB2 * peakX2L - peakA1 * peakY1L - peakA2 * peakY2L
                    peakX2L = peakX1L; peakX1L = hpfOut; peakY2L = peakY1L; peakY1L = peakOut

                    val lpfOut = lpfB0 * peakOut + lpfB1 * lpfX1L + lpfB2 * lpfX2L - lpfA1 * lpfY1L - lpfA2 * lpfY2L
                    lpfX2L = lpfX1L; lpfX1L = peakOut; lpfY2L = peakY1L; lpfY1L = lpfOut
                    s = lpfOut
                }
                if (loudnessOn) {
                    s *= gain
                }
                val outSample = softClip(s)
                outputBuffer.putShort((outSample * 32767.0f).toInt().coerceIn(-32768, 32767).toShort())
            }
        }

        outputBuffer.flip()
    }

    private fun softClip(sample: Float): Float {
        val absVal = abs(sample)
        return if (absVal <= 0.8f) {
            sample
        } else {
            val sign = if (sample > 0f) 1f else -1f
            val excess = absVal - 0.8f
            sign * (0.8f + (excess / (1.0f + excess * 2.0f))).coerceAtMost(0.999f)
        }
    }
}
