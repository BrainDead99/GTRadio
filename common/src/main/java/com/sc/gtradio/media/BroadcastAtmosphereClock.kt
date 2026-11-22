package com.sc.gtradio.media

import android.content.Context
import androidx.preference.PreferenceManager
import com.sc.gtradio.common.GTRadioLogger
import java.util.Calendar

enum class SeriesEra {
    GEN2,
    GEN3_RPF3,
    GEN3_RPF7
}

enum class TimeOfDayBucket {
    MORNING,
    AFTERNOON,
    EVENING,
    NIGHT,
    GENERAL
}

enum class WeatherState {
    SUN,
    CLOUD,
    FOG,
    RAIN,
    WIND,
    STORM
}

object BroadcastAtmosphereClock {
    const val PREF_KEY_ATMOSPHERIC_CYCLE = "atmospheric_time_weather_cycle_enabled"

    // GEN2: 24 real minutes per day (1,440,000 ms) -> 1 real minute per in-game hour (60,000 ms)
    private const val GEN2_DAY_MS = 24L * 60_000L
    private const val GEN2_HOUR_MS = 60_000L

    // GEN3: 48 real minutes per day (2,880,000 ms) -> 2 real minutes per in-game hour (120,000 ms)
    private const val GEN3_DAY_MS = 48L * 60_000L
    private const val GEN3_HOUR_MS = 120_000L

    // Salt seeds for continuous hash PRNG across eras
    private const val SALT_GEN2 = 0x5A41L       // "SA"
    private const val SALT_RPF3 = 0x4956L       // "IV"
    private const val SALT_RPF7 = 0x5635L       // "V"

    fun isAtmosphericCycleEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(PREF_KEY_ATMOSPHERIC_CYCLE, true)
    }

    fun getTimeOfDay(context: Context, era: SeriesEra, nowMs: Long = System.currentTimeMillis()): TimeOfDayBucket {
        val enabled = isAtmosphericCycleEnabled(context)
        val bucket = if (enabled) {
            getInGameTimeOfDay(era, nowMs)
        } else {
            getLocalTimeOfDay(era)
        }
        GTRadioLogger.logVerbose("ATMOS") {
            "getTimeOfDay: era=$era, mode=${if (enabled) "IN_GAME_CYCLE" else "LOCAL_CLOCK"}, bucket=$bucket"
        }
        return bucket
    }

    fun getWeather(context: Context, era: SeriesEra, nowMs: Long = System.currentTimeMillis()): WeatherState {
        val enabled = isAtmosphericCycleEnabled(context)
        val weather = if (enabled) {
            getInGameWeather(era, nowMs)
        } else {
            getLocalWeather(era, nowMs)
        }
        GTRadioLogger.logVerbose("ATMOS") {
            "getWeather: era=$era, mode=${if (enabled) "IN_GAME_CYCLE" else "LOCAL_CLOCK"}, weather=$weather"
        }
        return weather
    }

    fun getInGameTimeOfDay(era: SeriesEra, nowMs: Long): TimeOfDayBucket {
        return when (era) {
            SeriesEra.GEN2 -> {
                val inGameHour = ((nowMs % GEN2_DAY_MS) / GEN2_HOUR_MS).toInt()
                when (inGameHour) {
                    in 3..11 -> TimeOfDayBucket.MORNING
                    in 12..16 -> TimeOfDayBucket.GENERAL
                    in 17..22 -> TimeOfDayBucket.EVENING
                    else -> TimeOfDayBucket.NIGHT // 23..2
                }
            }
            SeriesEra.GEN3_RPF3, SeriesEra.GEN3_RPF7 -> {
                val inGameHour = ((nowMs % GEN3_DAY_MS) / GEN3_HOUR_MS).toInt()
                when (inGameHour) {
                    in 5..11 -> TimeOfDayBucket.MORNING
                    in 12..16 -> TimeOfDayBucket.AFTERNOON
                    in 17..21 -> TimeOfDayBucket.EVENING
                    else -> TimeOfDayBucket.NIGHT // 22..4
                }
            }
        }
    }

    fun getLocalTimeOfDay(era: SeriesEra): TimeOfDayBucket {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (era) {
            SeriesEra.GEN2 -> {
                when (hour) {
                    in 3..11 -> TimeOfDayBucket.MORNING
                    in 12..16 -> TimeOfDayBucket.GENERAL
                    in 17..22 -> TimeOfDayBucket.EVENING
                    else -> TimeOfDayBucket.NIGHT // 23..2
                }
            }
            SeriesEra.GEN3_RPF3, SeriesEra.GEN3_RPF7 -> {
                when (hour) {
                    in 5..11 -> TimeOfDayBucket.MORNING
                    in 12..16 -> TimeOfDayBucket.AFTERNOON
                    in 17..21 -> TimeOfDayBucket.EVENING
                    else -> TimeOfDayBucket.NIGHT // 22..4
                }
            }
        }
    }

    /**
     * Deterministic Markov weather progression for authentic in-game cycle:
     * - GEN2: 4-state Markov weather pool {SUN, FOG, RAIN, STORM}, holding for 3..10 in-game hours (3..10 real min).
     * - GEN3: 5-state Markov weather pool {SUN, CLOUD, FOG, RAIN, WIND}, holding for 3..10 in-game hours (6..20 real min).
     *
     * Driven strictly by nowMs continuous global epoch without background threads, timers, or drift.
     */
    fun getInGameWeather(era: SeriesEra, nowMs: Long): WeatherState {
        val blockSizeMs = if (era == SeriesEra.GEN2) GEN2_HOUR_MS else GEN3_HOUR_MS
        val eraSalt = when (era) {
            SeriesEra.GEN2 -> SALT_GEN2
            SeriesEra.GEN3_RPF3 -> SALT_RPF3
            SeriesEra.GEN3_RPF7 -> SALT_RPF7
        }

        // 12-hour windowing ensures constant-time execution (<5 microseconds) from the current epoch anchor
        val windowDurationMs = 12L * 3600_000L
        val windowIndex = nowMs / windowDurationMs
        val windowStartBlock = (windowIndex * windowDurationMs) / blockSizeMs
        val currentBlock = nowMs / blockSizeMs

        var state = splitMix64(windowIndex xor eraSalt)
        var currentWeather = initialInGameWeather(era, state)
        var blockCursor = windowStartBlock

        while (blockCursor <= currentBlock) {
            state = splitMix64(state)
            // Variable front duration between 3 and 10 in-game hours
            val frontDuration = 3 + ((state ushr 1) % 8).toInt()
            state = splitMix64(state)
            currentWeather = nextInGameWeatherMarkov(era, currentWeather, state)
            blockCursor += frontDuration
        }

        return currentWeather
    }

    /**
     * Offline real-time improvised weather when Atmospheric Cycle toggle is disabled:
     * - Scales weather fronts to realistic ~2 to 6 real hours seeded from local date + hour block.
     * - Clock-aware weighting:
     *   - Dawn/late night (22:00-06:59): suppress SUN weather reports (never announce blazing sunshine at night!).
     *     Higher FOG probability around dawn (04:00-07:59).
     *   - Daytime (07:00-17:59): allow full native pool including SUN.
     *   - Evening (18:00-21:59): allow full native pool with natural twilight weighting.
     */
    fun getLocalWeather(era: SeriesEra, nowMs: Long): WeatherState {
        val cal = Calendar.getInstance()
        val localHour = cal.get(Calendar.HOUR_OF_DAY)
        val localDayOfYear = cal.get(Calendar.DAY_OF_YEAR)
        val localYear = cal.get(Calendar.YEAR)

        // 3-hour front block in real local time
        val frontBlock = localHour / 3
        val eraSalt = when (era) {
            SeriesEra.GEN2 -> SALT_GEN2
            SeriesEra.GEN3_RPF3 -> SALT_RPF3
            SeriesEra.GEN3_RPF7 -> SALT_RPF7
        }

        val seed = (localYear.toLong() * 10000L + localDayOfYear.toLong() * 10L + frontBlock.toLong()) xor eraSalt
        val roll = (splitMix64(seed) ushr 1) % 100

        return when (era) {
            SeriesEra.GEN2 -> {
                // GEN2 native pool: {SUN, FOG, RAIN, STORM}
                when {
                    localHour in 22..23 || localHour in 0..3 -> {
                        // Late night: strictly NO SUN
                        when {
                            roll < 45 -> WeatherState.RAIN
                            roll < 75 -> WeatherState.STORM
                            else -> WeatherState.FOG
                        }
                    }
                    localHour in 4..6 -> {
                        // Dawn: strictly NO SUN, heavy FOG weighting
                        when {
                            roll < 60 -> WeatherState.FOG
                            roll < 85 -> WeatherState.RAIN
                            else -> WeatherState.STORM
                        }
                    }
                    localHour == 7 -> {
                        // Early morning transition: low sun, fog, rain
                        when {
                            roll < 45 -> WeatherState.FOG
                            roll < 75 -> WeatherState.SUN
                            roll < 90 -> WeatherState.RAIN
                            else -> WeatherState.STORM
                        }
                    }
                    localHour in 8..17 -> {
                        // Daytime: full pool, sunny dominant
                        when {
                            roll < 55 -> WeatherState.SUN
                            roll < 75 -> WeatherState.RAIN
                            roll < 90 -> WeatherState.FOG
                            else -> WeatherState.STORM
                        }
                    }
                    else -> {
                        // Evening (18..21): full pool, balanced
                        when {
                            roll < 40 -> WeatherState.SUN
                            roll < 70 -> WeatherState.RAIN
                            roll < 88 -> WeatherState.FOG
                            else -> WeatherState.STORM
                        }
                    }
                }
            }
            SeriesEra.GEN3_RPF3, SeriesEra.GEN3_RPF7 -> {
                // GEN3 native pool: {SUN, CLOUD, FOG, RAIN, WIND}
                when {
                    localHour in 22..23 || localHour in 0..3 -> {
                        // Late night: strictly NO SUN
                        when {
                            roll < 40 -> WeatherState.CLOUD
                            roll < 65 -> WeatherState.RAIN
                            roll < 85 -> WeatherState.WIND
                            else -> WeatherState.FOG
                        }
                    }
                    localHour in 4..6 -> {
                        // Dawn: strictly NO SUN, heavy FOG weighting
                        when {
                            roll < 50 -> WeatherState.FOG
                            roll < 75 -> WeatherState.CLOUD
                            roll < 90 -> WeatherState.RAIN
                            else -> WeatherState.WIND
                        }
                    }
                    localHour == 7 -> {
                        // Early morning transition
                        when {
                            roll < 35 -> WeatherState.CLOUD
                            roll < 65 -> WeatherState.SUN
                            roll < 85 -> WeatherState.FOG
                            else -> WeatherState.WIND
                        }
                    }
                    localHour in 8..17 -> {
                        // Daytime: full pool, sunny dominant
                        when {
                            roll < 45 -> WeatherState.SUN
                            roll < 70 -> WeatherState.CLOUD
                            roll < 85 -> WeatherState.RAIN
                            roll < 95 -> WeatherState.WIND
                            else -> WeatherState.FOG
                        }
                    }
                    else -> {
                        // Evening (18..21): full pool, overcast / sunset mix
                        when {
                            roll < 35 -> WeatherState.SUN
                            roll < 65 -> WeatherState.CLOUD
                            roll < 82 -> WeatherState.RAIN
                            roll < 93 -> WeatherState.WIND
                            else -> WeatherState.FOG
                        }
                    }
                }
            }
        }
    }

    private fun initialInGameWeather(era: SeriesEra, seed: Long): WeatherState {
        val roll = ((seed ushr 1) % 100).toInt()
        return if (era == SeriesEra.GEN2) {
            when {
                roll < 55 -> WeatherState.SUN
                roll < 75 -> WeatherState.FOG
                roll < 90 -> WeatherState.RAIN
                else -> WeatherState.STORM
            }
        } else {
            when {
                roll < 45 -> WeatherState.SUN
                roll < 70 -> WeatherState.CLOUD
                roll < 85 -> WeatherState.RAIN
                roll < 95 -> WeatherState.WIND
                else -> WeatherState.FOG
            }
        }
    }

    private fun nextInGameWeatherMarkov(era: SeriesEra, current: WeatherState, seed: Long): WeatherState {
        val roll = ((seed ushr 1) % 100).toInt()
        return if (era == SeriesEra.GEN2) {
            // Markov transition for GEN2 {SUN, FOG, RAIN, STORM}
            when (current) {
                WeatherState.SUN -> when {
                    roll < 45 -> WeatherState.SUN
                    roll < 70 -> WeatherState.FOG
                    roll < 90 -> WeatherState.RAIN
                    else -> WeatherState.STORM
                }
                WeatherState.FOG -> when {
                    roll < 40 -> WeatherState.SUN
                    roll < 75 -> WeatherState.RAIN
                    roll < 90 -> WeatherState.STORM
                    else -> WeatherState.FOG
                }
                WeatherState.RAIN -> when {
                    roll < 35 -> WeatherState.STORM
                    roll < 65 -> WeatherState.FOG
                    roll < 90 -> WeatherState.SUN
                    else -> WeatherState.RAIN
                }
                WeatherState.STORM -> when {
                    roll < 50 -> WeatherState.RAIN
                    roll < 80 -> WeatherState.FOG
                    else -> WeatherState.SUN
                }
                else -> WeatherState.SUN
            }
        } else {
            // Markov transition for GEN3 {SUN, CLOUD, FOG, RAIN, WIND}
            when (current) {
                WeatherState.SUN -> when {
                    roll < 40 -> WeatherState.SUN
                    roll < 70 -> WeatherState.CLOUD
                    roll < 85 -> WeatherState.WIND
                    roll < 95 -> WeatherState.FOG
                    else -> WeatherState.RAIN
                }
                WeatherState.CLOUD -> when {
                    roll < 35 -> WeatherState.SUN
                    roll < 65 -> WeatherState.RAIN
                    roll < 85 -> WeatherState.WIND
                    roll < 95 -> WeatherState.FOG
                    else -> WeatherState.CLOUD
                }
                WeatherState.FOG -> when {
                    roll < 35 -> WeatherState.CLOUD
                    roll < 65 -> WeatherState.SUN
                    roll < 90 -> WeatherState.RAIN
                    else -> WeatherState.FOG
                }
                WeatherState.RAIN -> when {
                    roll < 40 -> WeatherState.CLOUD
                    roll < 65 -> WeatherState.WIND
                    roll < 85 -> WeatherState.FOG
                    else -> WeatherState.SUN
                }
                WeatherState.WIND -> when {
                    roll < 35 -> WeatherState.CLOUD
                    roll < 65 -> WeatherState.RAIN
                    roll < 90 -> WeatherState.SUN
                    else -> WeatherState.WIND
                }
                else -> WeatherState.SUN
            }
        }
    }

    private fun splitMix64(seed: Long): Long {
        var z = (seed xor (seed ushr 30)) * -0x61c8864680b583ebL
        z = (z xor (z ushr 27)) * -0x6b2fb644f55e5cbfL
        return z xor (z ushr 31)
    }
}
