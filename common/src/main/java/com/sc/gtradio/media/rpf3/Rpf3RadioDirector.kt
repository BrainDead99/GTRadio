package com.sc.gtradio.media.rpf3

import android.content.Context
import com.sc.gtradio.common.GTRadioLogger
import com.sc.gtradio.media.BroadcastAtmosphereClock
import com.sc.gtradio.media.SeriesEra
import com.sc.gtradio.media.TimeOfDayBucket
import com.sc.gtradio.media.WeatherState
import com.sc.gtradio.media.core.Gen3BroadcastEvent
import com.sc.gtradio.media.core.Gen3DirectorPhase
import com.sc.gtradio.media.core.Gen3DirectorSnapshot
import com.sc.gtradio.media.core.RadioSegmentType
import com.sc.gtradio.media.core.RandomizedDualHeadQueue
import com.sc.gtradio.media.core.StandaloneBreakClip
import java.util.Random

class Rpf3RadioDirector<TSong, TClip>(
    private val context: Context,
    val stationName: String,
    val isTalkRadio: Boolean,
    val isContinuousMix: Boolean,
    val isNoCommercialStation: Boolean,
    var maxAdsPerBreak: Int,
    private val rng: Random = Random()
) {
    var adsEnabled: Boolean = true
    var newsReportsEnabled: Boolean = true
    var weatherChatterEnabled: Boolean = true

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

    val weatherSunQueue = RandomizedDualHeadQueue<TClip>(emptyList(), rng)
    val weatherCloudQueue = RandomizedDualHeadQueue<TClip>(emptyList(), rng)
    val weatherFogQueue = RandomizedDualHeadQueue<TClip>(emptyList(), rng)
    val weatherRainQueue = RandomizedDualHeadQueue<TClip>(emptyList(), rng)
    val weatherWindQueue = RandomizedDualHeadQueue<TClip>(emptyList(), rng)
    val weatherQueue = RandomizedDualHeadQueue<TClip>(emptyList(), rng)

    var morningDjList: List<TClip> = emptyList()
    var afternoonDjList: List<TClip> = emptyList()
    var eveningDjList: List<TClip> = emptyList()
    var nightDjList: List<TClip> = emptyList()

    private var activeTodBucket: TimeOfDayBucket? = null

    var currentPhase: Gen3DirectorPhase = Gen3DirectorPhase.MUSIC_SWEEP
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
    var breakScheduled: Boolean = false
        private set

    private fun pickNewSweepLength(): Int {
        return if (isTalkRadio || isContinuousMix || isNoCommercialStation) 1 else rng.nextInt(3) + 3 // 3..5 songs
    }

    fun rebuildTimeOfDayQueueIfNeeded() {
        val currentBucket = BroadcastAtmosphereClock.getTimeOfDay(context, SeriesEra.GEN3_RPF3)
        if (currentBucket != activeTodBucket) {
            activeTodBucket = currentBucket
            val list = when (currentBucket) {
                TimeOfDayBucket.MORNING -> morningDjList
                TimeOfDayBucket.AFTERNOON -> afternoonDjList.ifEmpty { morningDjList }
                TimeOfDayBucket.EVENING -> eveningDjList.ifEmpty { afternoonDjList.ifEmpty { morningDjList } }
                TimeOfDayBucket.NIGHT -> nightDjList.ifEmpty { eveningDjList.ifEmpty { morningDjList } }
                TimeOfDayBucket.GENERAL -> emptyList()
            }
            timeOfDayDjQueue.reset(list)
        }
    }

    fun hasAnyWeather(): Boolean {
        return weatherSunQueue.isNotEmpty() || weatherCloudQueue.isNotEmpty() ||
                weatherFogQueue.isNotEmpty() || weatherRainQueue.isNotEmpty() ||
                weatherWindQueue.isNotEmpty() || weatherQueue.isNotEmpty()
    }

    private fun pickWeatherClip(state: WeatherState): TClip? {
        val q = when (state) {
            WeatherState.SUN -> weatherSunQueue
            WeatherState.CLOUD -> weatherCloudQueue
            WeatherState.FOG -> weatherFogQueue
            WeatherState.RAIN, WeatherState.STORM -> weatherRainQueue
            WeatherState.WIND -> weatherWindQueue
        }
        return if (q.isNotEmpty()) q.next() else if (weatherQueue.isNotEmpty()) weatherQueue.next() else null
    }

    fun canRunBreak(): Boolean {
        if (isNoCommercialStation || isTalkRadio || isContinuousMix) return false
        val canAd = adsEnabled && advertQueue.isNotEmpty()
        val canNews = newsReportsEnabled && newsQueue.isNotEmpty()
        val canWeather = weatherChatterEnabled && hasAnyWeather()
        return canAd || canNews || canWeather
    }

    private fun planBreakBlock(): Gen3BroadcastEvent.BreakBlockEvent<TClip> {
        val standalone = ArrayList<StandaloneBreakClip<TClip>>()
        val canAd = adsEnabled && advertQueue.isNotEmpty()
        val canNews = newsReportsEnabled && newsQueue.isNotEmpty()
        val canWeather = weatherChatterEnabled && hasAnyWeather()

        var outroLeadIn: TClip? = null
        var outroLeadInRole: String? = null

        val weatherRoll = if (canWeather) rng.nextInt(100) else 100
        if (canWeather && weatherRoll < 25) {
            // Weather block
            if (toWeatherQueue.isNotEmpty() && rng.nextInt(100) < 65) {
                outroLeadIn = toWeatherQueue.next()
                outroLeadInRole = "TO_WEATHER"
            }
            val targetWeather = BroadcastAtmosphereClock.getWeather(context, SeriesEra.GEN3_RPF3)
            val wClip = pickWeatherClip(targetWeather)
            if (wClip != null) {
                standalone.add(StandaloneBreakClip(wClip, RadioSegmentType.WeatherReport, "WEATHER_$targetWeather"))
            }
            if (canAd) {
                val rawAdCount = if (rng.nextBoolean()) 1 else 2
                val adCount = minOf(maxAdsPerBreak, rawAdCount)
                repeat(adCount) { i ->
                    if (advertQueue.isNotEmpty()) {
                        standalone.add(StandaloneBreakClip(advertQueue.next(), RadioSegmentType.Commercial, "AD_${i + 1}"))
                    }
                }
            }
        } else if (canNews && (!canAd || rng.nextInt(100) < 35)) {
            // News block: 1-2 news clips (50% 1, 50% 2) + optional ad
            if (toNewsQueue.isNotEmpty() && rng.nextInt(100) < 65) {
                outroLeadIn = toNewsQueue.next()
                outroLeadInRole = "TO_NEWS"
            }
            val newsCount = if (newsQueue.size() >= 2 && rng.nextBoolean()) 2 else 1
            repeat(newsCount) { i ->
                if (newsQueue.isNotEmpty()) {
                    standalone.add(StandaloneBreakClip(newsQueue.next(), RadioSegmentType.NewsReport, "NEWS_${i + 1}"))
                }
            }
            if (canAd) {
                val rawAdCount = if (rng.nextBoolean()) 1 else 2
                val adCount = minOf(maxAdsPerBreak, rawAdCount)
                repeat(adCount) { i ->
                    if (advertQueue.isNotEmpty()) {
                        standalone.add(StandaloneBreakClip(advertQueue.next(), RadioSegmentType.Commercial, "AD_${i + 1}"))
                    }
                }
            }
        } else if (canAd) {
            // Commercial block: capped by maxAdsPerBreak (Russian/Spanish = 1 ad; English = 60% 1 ad / 40% 2 ads)
            if (toAdQueue.isNotEmpty() && rng.nextInt(100) < 65) {
                outroLeadIn = toAdQueue.next()
                outroLeadInRole = "TO_AD"
            }
            val rawAdCount = if (maxAdsPerBreak <= 1) 1 else if (rng.nextInt(100) < 60) 1 else 2
            val adCount = minOf(maxAdsPerBreak, rawAdCount)
            repeat(adCount) { i ->
                if (advertQueue.isNotEmpty()) {
                    standalone.add(StandaloneBreakClip(advertQueue.next(), RadioSegmentType.Commercial, "AD_${i + 1}"))
                }
            }
        }

        // Return imaging (~85% Station ID in standaloneClips)
        if (standalone.isNotEmpty() && idQueue.isNotEmpty() && rng.nextInt(100) < 85) {
            standalone.add(StandaloneBreakClip(idQueue.next(), RadioSegmentType.Announcer, "RETURN_ID"))
        }

        return Gen3BroadcastEvent.BreakBlockEvent(
            outroDryLeadIn = outroLeadIn,
            outroLeadInRole = outroLeadInRole,
            standaloneClips = standalone
        )
    }

    @Synchronized
    fun pickNextEvent(pickIntro: (TSong) -> TClip? = { null }): Gen3BroadcastEvent<TSong, TClip> {
        rebuildTimeOfDayQueueIfNeeded()

        if (songsRemainingInSweep <= 0 && !breakScheduled) {
            if (canRunBreak()) {
                val breakBlock = planBreakBlock()
                if (breakBlock.standaloneClips.isNotEmpty()) {
                    breakScheduled = true
                    currentPhase = Gen3DirectorPhase.BREAK_BLOCK
                    nextPlannedAction = "PLAY_BREAK_BLOCK (${breakBlock.standaloneClips.size} standalone clips)"
                    logSnapshot()
                    return breakBlock
                }
            }
            startNewSweep()
        }

        if (breakScheduled || songsRemainingInSweep <= 0) {
            startNewSweep()
        }

        // Mid-sweep interlude (~40% if no intro on next song, ~18% otherwise)
        if (currentPhase == Gen3DirectorPhase.MUSIC_SWEEP && lastSegmentType == RadioSegmentType.Song && consecutiveSongs >= 1) {
            val nextSong = songQueue.peek()
            val nextSongHasIntro = nextSong != null && !isTalkRadio && pickIntro(nextSong) != null
            val interludeChance = if (!nextSongHasIntro) 40 else 18
            if (rng.nextInt(100) < interludeChance) {
                val interlude = pickMidSweepInterlude()
                if (interlude != null) {
                    currentPhase = Gen3DirectorPhase.INTERLUDE
                    lastSegmentType = interlude.segmentType
                    consecutiveSongs = 0
                    nextPlannedAction = "MID_SWEEP_INTERLUDE (${interlude.role})"
                    logSnapshot()
                    return interlude
                }
            }
        }

        currentPhase = Gen3DirectorPhase.MUSIC_SWEEP
        if (songQueue.isEmpty()) {
            throw IllegalStateException("Rpf3RadioDirector[$stationName]: songQueue is empty")
        }
        val song = songQueue.next()
        val intro = if (!isTalkRadio && rng.nextInt(100) < 72) pickIntro(song) else null
        songsRemainingInSweep--
        consecutiveSongs++
        lastSegmentType = RadioSegmentType.Song
        nextPlannedAction = "PLAY_SONG (remainingInSweep=$songsRemainingInSweep)"
        logSnapshot()
        return Gen3BroadcastEvent.SongEvent(song, intro, isFallbackIntroOverlay = false)
    }

    private fun pickMidSweepInterlude(): Gen3BroadcastEvent.InterludeEvent<TClip>? {
        val hasDj = timeOfDayDjQueue.isNotEmpty() || generalDjQueue.isNotEmpty()
        val hasId = idQueue.isNotEmpty()
        val hasSolo = soloQueue.isNotEmpty()
        if (!hasDj && !hasId && !hasSolo) return null

        val roll = rng.nextInt(100)
        return when {
            hasDj && roll < 50 -> {
                val clip = if (timeOfDayDjQueue.isNotEmpty() && rng.nextBoolean()) timeOfDayDjQueue.next() else generalDjQueue.next()
                Gen3BroadcastEvent.InterludeEvent(clip, RadioSegmentType.DJChatter, "INTERLUDE_CHATTER", isDryOverlayEligible = true)
            }
            hasId && roll < 80 -> {
                Gen3BroadcastEvent.InterludeEvent(idQueue.next(), RadioSegmentType.Announcer, "INTERLUDE_ID", isDryOverlayEligible = false)
            }
            hasSolo -> {
                Gen3BroadcastEvent.InterludeEvent(soloQueue.next(), RadioSegmentType.DJChatter, "INTERLUDE_SOLO", isDryOverlayEligible = false)
            }
            hasId -> {
                Gen3BroadcastEvent.InterludeEvent(idQueue.next(), RadioSegmentType.Announcer, "INTERLUDE_ID", isDryOverlayEligible = false)
            }
            hasDj -> {
                val clip = if (timeOfDayDjQueue.isNotEmpty()) timeOfDayDjQueue.next() else generalDjQueue.next()
                Gen3BroadcastEvent.InterludeEvent(clip, RadioSegmentType.DJChatter, "INTERLUDE_CHATTER", isDryOverlayEligible = true)
            }
            else -> null
        }
    }

    @Synchronized
    fun onBreakCompleted() {
        startNewSweep()
        logSnapshot()
    }

    private fun startNewSweep() {
        breakScheduled = false
        currentPhase = Gen3DirectorPhase.MUSIC_SWEEP
        currentSweepLength = pickNewSweepLength()
        songsRemainingInSweep = currentSweepLength
        consecutiveSongs = 0
        lastSegmentType = RadioSegmentType.None
        nextPlannedAction = "START_NEW_SWEEP (length=$currentSweepLength)"
    }

    fun getSnapshot(): Gen3DirectorSnapshot {
        return Gen3DirectorSnapshot(
            phase = currentPhase,
            songsRemainingInSweep = songsRemainingInSweep,
            currentSweepLength = currentSweepLength,
            consecutiveSongs = consecutiveSongs,
            pendingBreakClips = 0,
            lastAction = nextPlannedAction
        )
    }

    private fun logSnapshot() {
        GTRadioLogger.logVerbose("DIRECTOR") {
            "Rpf3[$stationName]: phase=$currentPhase, sweepRemaining=$songsRemainingInSweep/$currentSweepLength, action=$nextPlannedAction"
        }
    }
}
