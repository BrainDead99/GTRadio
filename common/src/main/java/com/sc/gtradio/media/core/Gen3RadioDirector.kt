package com.sc.gtradio.media.core

import android.content.Context
import com.sc.gtradio.common.GTRadioLogger
import com.sc.gtradio.media.BroadcastAtmosphereClock
import com.sc.gtradio.media.SeriesEra
import com.sc.gtradio.media.TimeOfDayBucket
import com.sc.gtradio.media.WeatherState
import java.util.Random

enum class DirectorPhase {
    MUSIC_SWEEP,
    BREAK_BLOCK,
    INTERLUDE
}

enum class BreakType {
    COMMERCIAL,
    NEWS,
    WEATHER
}

data class DirectorStateSnapshot(
    val phase: DirectorPhase,
    val sweepLength: Int,
    val songsRemainingInSweep: Int,
    val consecutiveSongs: Int,
    val pendingBreakQueueSize: Int,
    val lastSegmentType: RadioSegmentType,
    val nextPlannedAction: String
)

data class DirectorBreakClip<TClip>(
    val clip: TClip,
    val segmentType: RadioSegmentType,
    val label: String
)

sealed class NextBroadcastEvent<out TSong, out TClip> {
    data class SongSweepItem<TSong, TClip>(
        val song: TSong,
        val djIntro: TClip? = null,
        val songsRemainingInSweep: Int
    ) : NextBroadcastEvent<TSong, TClip>()

    data class BreakBlockItem<TClip>(
        val clips: List<DirectorBreakClip<TClip>>,
        val breakType: BreakType
    ) : NextBroadcastEvent<Nothing, TClip>()

    data class MidSweepInterludeItem<TClip>(
        val clip: TClip,
        val segmentType: RadioSegmentType
    ) : NextBroadcastEvent<Nothing, TClip>()
}

/**
 * Unified Neutral Broadcast Director for Gen3 Radio Stations (RPF3 and RPF7).
 *
 * Implements authentic Gen3 broadcast pacing:
 * - Discrete music stations run 3 to 5 songs per sweep before triggering a network break block.
 * - Talk radio stations run 1 show segment before triggering a break block.
 * - Multi-clip break blocks strictly respect user preferences for Ads, News, and Weather:
 *   - Optional lead-in (TO_AD, TO_NEWS, TO_WEATHER) at ~65% probability when available.
 *   - News blocks (~35% chance when both News and Ads enabled): 1 News report + 1–2 Adverts.
 *   - Commercial blocks: 2–3 Adverts back-to-back (weighted: 20% = 1 ad, 50% = 2 ads, 30% = 3 ads).
 *   - Weather blocks (RPF3 when weather enabled): optional TO_WEATHER + active Markov weather clip (pairs with 1–2 ads).
 *   - Post-break return imaging at ~85% probability (Station ID or short DJ solo) before starting the next sweep.
 * - Backed by RandomizedDualHeadQueue (RDHQ) ensuring playlist entropy without repetition.
 */
class Gen3RadioDirector<TSong, TClip>(
    private val context: Context,
    private val era: SeriesEra,
    val stationName: String,
    val isTalkRadio: Boolean,
    private val rng: Random = Random()
) {
    var adsEnabled: Boolean = false
    var newsReportsEnabled: Boolean = false
    var weatherChatterEnabled: Boolean = false

    // RDHQ clip & song queues
    val songQueue = RandomizedDualHeadQueue<TSong>(emptyList(), rng)
    val idQueue = RandomizedDualHeadQueue<TClip>(emptyList(), rng)
    val soloQueue = RandomizedDualHeadQueue<TClip>(emptyList(), rng)
    val generalDjQueue = RandomizedDualHeadQueue<TClip>(emptyList(), rng)
    val timeOfDayDjQueue = RandomizedDualHeadQueue<TClip>(emptyList(), rng)
    val toAdQueue = RandomizedDualHeadQueue<TClip>(emptyList(), rng)
    val toNewsQueue = RandomizedDualHeadQueue<TClip>(emptyList(), rng)
    val toWeatherQueue = RandomizedDualHeadQueue<TClip>(emptyList(), rng)
    val advertQueue = RandomizedDualHeadQueue<TClip>(emptyList(), rng)
    val newsQueue = RandomizedDualHeadQueue<TClip>(emptyList(), rng)

    // Weather condition queues (RPF3)
    val weatherSunQueue = RandomizedDualHeadQueue<TClip>(emptyList(), rng)
    val weatherCloudQueue = RandomizedDualHeadQueue<TClip>(emptyList(), rng)
    val weatherFogQueue = RandomizedDualHeadQueue<TClip>(emptyList(), rng)
    val weatherRainQueue = RandomizedDualHeadQueue<TClip>(emptyList(), rng)
    val weatherWindQueue = RandomizedDualHeadQueue<TClip>(emptyList(), rng)
    val weatherQueue = RandomizedDualHeadQueue<TClip>(emptyList(), rng)

    // Master TOD pools
    var morningDjList: List<TClip> = emptyList()
    var afternoonDjList: List<TClip> = emptyList()
    var eveningDjList: List<TClip> = emptyList()
    var nightDjList: List<TClip> = emptyList()

    private var activeTodBucket: TimeOfDayBucket? = null

    // Pacing state machine
    var currentPhase: DirectorPhase = DirectorPhase.MUSIC_SWEEP
        private set
    var currentSweepLength: Int = pickNewSweepLength()
        private set
    var songsRemainingInSweep: Int = currentSweepLength
        private set
    var consecutiveSongs: Int = 0
        private set
    var lastSegmentType: RadioSegmentType = RadioSegmentType.None
        private set
    var nextPlannedAction: String = "INITIAL_SONG"
        private set

    val pendingBreakClips = ArrayList<DirectorBreakClip<TClip>>()

    private fun pickNewSweepLength(): Int {
        return if (isTalkRadio) 1 else rng.nextInt(3) + 3 // 3..5 songs
    }

    fun rebuildTimeOfDayQueueIfNeeded() {
        val currentBucket = BroadcastAtmosphereClock.getTimeOfDay(context, era)
        if (currentBucket != activeTodBucket) {
            activeTodBucket = currentBucket
            val list = when (currentBucket) {
                TimeOfDayBucket.MORNING -> morningDjList
                TimeOfDayBucket.AFTERNOON -> afternoonDjList.ifEmpty { morningDjList }
                TimeOfDayBucket.EVENING -> eveningDjList.ifEmpty { afternoonDjList.ifEmpty { morningDjList } }
                TimeOfDayBucket.NIGHT -> nightDjList.ifEmpty { eveningDjList.ifEmpty { morningDjList } }
                else -> emptyList()
            }
            timeOfDayDjQueue.reset(list)
            GTRadioLogger.logVerbose("DIRECTOR") {
                "[DIRECTOR] $stationName: Rebuilt TOD queue for $currentBucket (${list.size} clips)"
            }
        }
    }

    fun hasAnyWeather(): Boolean {
        return weatherSunQueue.isNotEmpty() || weatherCloudQueue.isNotEmpty() ||
                weatherFogQueue.isNotEmpty() || weatherRainQueue.isNotEmpty() ||
                weatherWindQueue.isNotEmpty() || weatherQueue.isNotEmpty()
    }

    fun hasAnyDjChatter(): Boolean {
        rebuildTimeOfDayQueueIfNeeded()
        return generalDjQueue.isNotEmpty() || timeOfDayDjQueue.isNotEmpty()
    }

    fun hasAnyAnnouncerOrSolo(): Boolean {
        return idQueue.isNotEmpty() || soloQueue.isNotEmpty()
    }

    fun canRunBreak(): Boolean {
        val canAd = adsEnabled && advertQueue.isNotEmpty()
        val canNews = newsReportsEnabled && newsQueue.isNotEmpty()
        val canWeather = weatherChatterEnabled && era == SeriesEra.GEN3_RPF3 && hasAnyWeather()
        return canAd || canNews || canWeather
    }

    /**
     * Advances the director state machine and returns the next broadcast event to play.
     */
    @Synchronized
    fun pickNextEvent(pickIntro: (TSong) -> TClip? = { null }): NextBroadcastEvent<TSong, TClip> {
        rebuildTimeOfDayQueueIfNeeded()

        // 1. If currently in a break block, or sweep is exhausted, prepare a break block if enabled
        if (songsRemainingInSweep <= 0) {
            if (canRunBreak()) {
                currentPhase = DirectorPhase.BREAK_BLOCK
                val breakClips = planBreakBlock()
                if (breakClips.isNotEmpty()) {
                    pendingBreakClips.clear()
                    pendingBreakClips.addAll(breakClips)
                    nextPlannedAction = "PLAY_BREAK_BLOCK (${breakClips.size} clips)"
                    logSnapshot()
                    val bType = when {
                        breakClips.any { it.segmentType == RadioSegmentType.NewsReport } -> BreakType.NEWS
                        breakClips.any { it.segmentType == RadioSegmentType.WeatherReport } -> BreakType.WEATHER
                        else -> BreakType.COMMERCIAL
                    }
                    return NextBroadcastEvent.BreakBlockItem(breakClips, bType)
                }
            }
            // If breaks disabled or no clips, smoothly start a new music sweep
            startNewSweep()
        }

        // 2. Check for optional brisk mid-sweep interlude between songs
        if (currentPhase == DirectorPhase.MUSIC_SWEEP && lastSegmentType == RadioSegmentType.Song && consecutiveSongs >= 1) {
            // ~20% probability of short Station ID, DJ solo, or TOD clip between songs
            if (rng.nextInt(100) < 20) {
                val interlude = pickMidSweepInterlude()
                if (interlude != null) {
                    currentPhase = DirectorPhase.INTERLUDE
                    lastSegmentType = interlude.segmentType
                    consecutiveSongs = 0
                    nextPlannedAction = "MID_SWEEP_INTERLUDE (${interlude.segmentType})"
                    logSnapshot()
                    return NextBroadcastEvent.MidSweepInterludeItem(interlude.clip, interlude.segmentType)
                }
            }
        }

        // 3. Play next song / show in sweep
        currentPhase = DirectorPhase.MUSIC_SWEEP
        if (songQueue.isEmpty()) {
            throw IllegalStateException("Gen3RadioDirector[$stationName]: songQueue is empty")
        }
        val song = songQueue.next()
        val intro = if (!isTalkRadio && rng.nextInt(100) < 60) pickIntro(song) else null
        songsRemainingInSweep--
        consecutiveSongs++
        lastSegmentType = RadioSegmentType.Song
        nextPlannedAction = "PLAY_SONG (remainingInSweep=$songsRemainingInSweep)"
        logSnapshot()
        return NextBroadcastEvent.SongSweepItem(song, intro, songsRemainingInSweep)
    }

    /**
     * Called when a multi-clip break completes playing in ExoPlayer, transitioning back to music.
     */
    @Synchronized
    fun onBreakCompleted() {
        pendingBreakClips.clear()
        startNewSweep()
        logSnapshot()
    }

    private fun startNewSweep() {
        currentPhase = DirectorPhase.MUSIC_SWEEP
        currentSweepLength = pickNewSweepLength()
        songsRemainingInSweep = currentSweepLength
        consecutiveSongs = 0
        lastSegmentType = RadioSegmentType.None
        nextPlannedAction = "START_NEW_SWEEP (length=$currentSweepLength)"
    }

    private fun planBreakBlock(): List<DirectorBreakClip<TClip>> {
        val clips = ArrayList<DirectorBreakClip<TClip>>()
        val canAd = adsEnabled && advertQueue.isNotEmpty()
        val canNews = newsReportsEnabled && newsQueue.isNotEmpty()
        val canWeather = weatherChatterEnabled && era == SeriesEra.GEN3_RPF3 && hasAnyWeather()

        if (!canAd && !canNews && !canWeather) return emptyList()

        // 1. Determine break focus: Weather (~25% on RPF3), News (~35% when both news and ads enabled), or Commercial
        val weatherRoll = if (canWeather) rng.nextInt(100) else 100
        if (canWeather && weatherRoll < 25) {
            // Weather block
            if (toWeatherQueue.isNotEmpty() && rng.nextInt(100) < 65) {
                clips.add(DirectorBreakClip(toWeatherQueue.next(), RadioSegmentType.Announcer, "TO_WEATHER"))
            }
            val targetWeather = BroadcastAtmosphereClock.getWeather(context, era)
            val wClip = pickWeatherClip(targetWeather)
            if (wClip != null) {
                clips.add(DirectorBreakClip(wClip, RadioSegmentType.WeatherReport, "WEATHER_$targetWeather"))
            }
            if (canAd) {
                val adCount = if (rng.nextBoolean()) 1 else 2
                repeat(adCount) { i ->
                    if (advertQueue.isNotEmpty()) {
                        clips.add(DirectorBreakClip(advertQueue.next(), RadioSegmentType.Commercial, "AD_${i + 1}"))
                    }
                }
            }
        } else if (canNews && (!canAd || rng.nextInt(100) < 35)) {
            // News block: 1 News report + 1-2 Adverts
            if (toNewsQueue.isNotEmpty() && rng.nextInt(100) < 65) {
                clips.add(DirectorBreakClip(toNewsQueue.next(), RadioSegmentType.Announcer, "TO_NEWS"))
            }
            if (newsQueue.isNotEmpty()) {
                clips.add(DirectorBreakClip(newsQueue.next(), RadioSegmentType.NewsReport, "NEWS_REPORT"))
            }
            if (canAd) {
                val adCount = if (rng.nextBoolean()) 1 else 2
                repeat(adCount) { i ->
                    if (advertQueue.isNotEmpty()) {
                        clips.add(DirectorBreakClip(advertQueue.next(), RadioSegmentType.Commercial, "AD_${i + 1}"))
                    }
                }
            }
        } else if (canAd) {
            // Standard Commercial block: 2-3 Adverts (20% = 1 ad, 50% = 2 ads, 30% = 3 ads)
            if (toAdQueue.isNotEmpty() && rng.nextInt(100) < 65) {
                clips.add(DirectorBreakClip(toAdQueue.next(), RadioSegmentType.Announcer, "TO_AD"))
            }
            val rollAdCount = rng.nextInt(100)
            val adCount = when {
                rollAdCount < 20 -> 1
                rollAdCount < 70 -> 2
                else -> 3
            }
            repeat(adCount) { i ->
                if (advertQueue.isNotEmpty()) {
                    clips.add(DirectorBreakClip(advertQueue.next(), RadioSegmentType.Commercial, "AD_${i + 1}"))
                }
            }
        }

        // 2. Post-Break Return Imaging: ~85% probability of Station ID or short DJ solo
        if (clips.isNotEmpty() && rng.nextInt(100) < 85) {
            val returnClip = pickReturnImagingClip()
            if (returnClip != null) {
                clips.add(DirectorBreakClip(returnClip.first, returnClip.second, "RETURN_IMAGING"))
            }
        }

        return clips
    }

    private fun pickReturnImagingClip(): Pair<TClip, RadioSegmentType>? {
        val hasId = idQueue.isNotEmpty()
        val hasSolo = soloQueue.isNotEmpty()
        if (!hasId && !hasSolo) return null

        val useId = if (hasId && hasSolo) rng.nextInt(100) < 70 else hasId
        return if (useId && hasId) {
            Pair(idQueue.next(), RadioSegmentType.Announcer)
        } else if (hasSolo) {
            Pair(soloQueue.next(), RadioSegmentType.DJChatter)
        } else {
            null
        }
    }

    private fun pickMidSweepInterlude(): DirectorBreakClip<TClip>? {
        val hasId = idQueue.isNotEmpty()
        val hasSolo = soloQueue.isNotEmpty()
        val hasGeneral = generalDjQueue.isNotEmpty()
        val hasTod = timeOfDayDjQueue.isNotEmpty()

        val roll = rng.nextInt(100)
        return when {
            hasId && roll < 40 -> DirectorBreakClip(idQueue.next(), RadioSegmentType.Announcer, "INTERLUDE_ID")
            hasSolo && roll < 70 -> DirectorBreakClip(soloQueue.next(), RadioSegmentType.DJChatter, "INTERLUDE_SOLO")
            hasTod && roll < 85 -> DirectorBreakClip(timeOfDayDjQueue.next(), RadioSegmentType.DJChatter, "INTERLUDE_TOD")
            hasGeneral -> DirectorBreakClip(generalDjQueue.next(), RadioSegmentType.DJChatter, "INTERLUDE_GENERAL")
            hasId -> DirectorBreakClip(idQueue.next(), RadioSegmentType.Announcer, "INTERLUDE_ID")
            else -> null
        }
    }

    private fun pickWeatherClip(targetWeather: WeatherState): TClip? {
        val chosenQueue = when (targetWeather) {
            WeatherState.SUN -> weatherSunQueue
            WeatherState.CLOUD -> weatherCloudQueue
            WeatherState.FOG -> weatherFogQueue
            WeatherState.RAIN -> weatherRainQueue
            WeatherState.WIND -> weatherWindQueue
            else -> weatherSunQueue
        }
        return if (chosenQueue.isNotEmpty()) {
            chosenQueue.next()
        } else if (weatherQueue.isNotEmpty()) {
            weatherQueue.next()
        } else {
            null
        }
    }

    fun getSnapshot(): DirectorStateSnapshot {
        return DirectorStateSnapshot(
            phase = currentPhase,
            sweepLength = currentSweepLength,
            songsRemainingInSweep = songsRemainingInSweep,
            consecutiveSongs = consecutiveSongs,
            pendingBreakQueueSize = pendingBreakClips.size,
            lastSegmentType = lastSegmentType,
            nextPlannedAction = nextPlannedAction
        )
    }

    private fun logSnapshot() {
        val s = getSnapshot()
        GTRadioLogger.logVerbose("DIRECTOR") {
            "[DIRECTOR] $stationName: phase=${s.phase}, remaining=${s.songsRemainingInSweep}/${s.sweepLength}, consecutive=${s.consecutiveSongs}, pendingBreaks=${s.pendingBreakQueueSize}, action=${s.nextPlannedAction}"
        }
    }

    @Synchronized
    fun reset() {
        currentPhase = DirectorPhase.MUSIC_SWEEP
        currentSweepLength = pickNewSweepLength()
        songsRemainingInSweep = currentSweepLength
        consecutiveSongs = 0
        lastSegmentType = RadioSegmentType.None
        pendingBreakClips.clear()
        nextPlannedAction = "RESET"
    }
}
