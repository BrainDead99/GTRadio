package com.sc.gtradio.media.rpf7

import android.content.Context
import com.sc.gtradio.common.GTRadioLogger
import com.sc.gtradio.media.BroadcastAtmosphereClock
import com.sc.gtradio.media.SeriesEra
import com.sc.gtradio.media.TimeOfDayBucket
import com.sc.gtradio.media.core.Gen3BroadcastEvent
import com.sc.gtradio.media.core.Gen3DirectorPhase
import com.sc.gtradio.media.core.Gen3DirectorSnapshot
import com.sc.gtradio.media.core.RadioSegmentType
import com.sc.gtradio.media.core.RandomizedDualHeadQueue
import com.sc.gtradio.media.core.StandaloneBreakClip
import java.util.Locale
import java.util.Random

enum class Rpf7AdvertPool {
    COUNTRY,
    GENERAL,
    NONE
}

data class Rpf7StationConfig(
    val isTalkRadio: Boolean,
    val isContinuousMix: Boolean,
    val advertPool: Rpf7AdvertPool,
    val allowsNews: Boolean,
    val allowsBackToBackAds: Boolean
) {
    companion object {
        fun resolve(rpfName: String, stationKey: String): Rpf7StationConfig {
            val key = stationKey.lowercase(Locale.ROOT)
            val rpf = rpfName.lowercase(Locale.ROOT)

            val isTalk = key.contains("radio_05") || key.contains("radio_11") || key.contains("talk") ||
                    rpf.contains("radio_05") || rpf.contains("radio_11") || rpf.contains("talk")

            val isMix = key.contains("radio_07") || key.contains("radio_08") || key.contains("radio_13") || key.contains("radio_14") ||
                    key.contains("radio_20") ||
                    rpf.contains("radio_07") || rpf.contains("radio_08") || rpf.contains("radio_13") || rpf.contains("radio_14") ||
                    rpf.contains("radio_20") ||
                    key.contains("soulwax") || key.contains("eastlos") || key.contains("worldwide") || key.contains("flylo") ||
                    key.contains("thelab")

            val isBlaineCounty = key.contains("radio_11") || rpf.contains("radio_11") || key.contains("11_talk_02")
            val isRebelRadio = key.contains("radio_06") || rpf.contains("radio_06") || key.contains("country")

            val advertPool = when {
                isMix -> Rpf7AdvertPool.NONE
                isRebelRadio || isBlaineCounty -> Rpf7AdvertPool.COUNTRY
                else -> Rpf7AdvertPool.GENERAL
            }

            val allowsNews = !isMix && !isBlaineCounty // Blaine County Radio has NO_NEWS flag in GTA V

            val isNoB2B = key.contains("radio_04") || rpf.contains("radio_04") || key.contains("punk") || // Channel X
                    key.contains("radio_12") || rpf.contains("radio_12") || key.contains("reggae") || // Blue Ark
                    key.contains("radio_18") || rpf.contains("radio_18") || key.contains("90s_rock") // Vinewood Boulevard

            val allowsBackToBackAds = !isNoB2B

            return Rpf7StationConfig(
                isTalkRadio = isTalk,
                isContinuousMix = isMix,
                advertPool = advertPool,
                allowsNews = allowsNews,
                allowsBackToBackAds = allowsBackToBackAds
            )
        }
    }
}

class Rpf7RadioDirector<TSong, TClip>(
    private val context: Context,
    val stationName: String,
    val config: Rpf7StationConfig,
    private val rng: Random = Random()
) {
    var adsEnabled: Boolean = true
    var newsReportsEnabled: Boolean = true
    var weatherChatterEnabled: Boolean = false // GTA V has no weather chatter

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

    var morningDjList: List<TClip> = emptyList()
    var eveningDjList: List<TClip> = emptyList()

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
        if (config.isTalkRadio || config.isContinuousMix) return 1
        // Tighter GTA V music sweeps (45% = 2, 40% = 3, 15% = 4)
        val roll = rng.nextInt(100)
        return when {
            roll < 45 -> 2
            roll < 85 -> 3
            else -> 4
        }
    }

    fun rebuildTimeOfDayQueueIfNeeded() {
        val currentBucket = BroadcastAtmosphereClock.getTimeOfDay(context, SeriesEra.GEN3_RPF7)
        if (currentBucket != activeTodBucket) {
            activeTodBucket = currentBucket
            val list = when (currentBucket) {
                TimeOfDayBucket.MORNING -> morningDjList
                TimeOfDayBucket.EVENING, TimeOfDayBucket.NIGHT -> eveningDjList.ifEmpty { morningDjList }
                TimeOfDayBucket.AFTERNOON, TimeOfDayBucket.GENERAL -> emptyList() // Cleanly fall back to generalDjQueue without duplicating
            }
            timeOfDayDjQueue.reset(list)
        }
    }

    fun canRunBreak(): Boolean {
        if (config.isContinuousMix) return false
        val canAd = adsEnabled && config.advertPool != Rpf7AdvertPool.NONE && advertQueue.isNotEmpty()
        val canNews = newsReportsEnabled && config.allowsNews && newsQueue.isNotEmpty()
        return canAd || canNews
    }

    private fun planBreakBlock(): Gen3BroadcastEvent.BreakBlockEvent<TClip> {
        val standalone = ArrayList<StandaloneBreakClip<TClip>>()
        val canAd = adsEnabled && config.advertPool != Rpf7AdvertPool.NONE && advertQueue.isNotEmpty()
        val canNews = newsReportsEnabled && config.allowsNews && newsQueue.isNotEmpty()

        var outroLeadIn: TClip? = null
        var outroLeadInRole: String? = null

        val isNewsBreak = canNews && (!canAd || rng.nextInt(100) < 30)

        if (isNewsBreak) {
            // News Break: optional to_news (~65% if available) + 1 single full Weazel News report
            if (toNewsQueue.isNotEmpty() && rng.nextInt(100) < 65) {
                outroLeadIn = toNewsQueue.next()
                outroLeadInRole = "TO_NEWS"
            }
            if (newsQueue.isNotEmpty()) {
                standalone.add(StandaloneBreakClip(newsQueue.next(), RadioSegmentType.NewsReport, "NEWS_REPORT"))
            }
        } else if (canAd) {
            // Commercial Break: optional to_ad (~65%) + 1 ad predominantly (or 2 if allowsBackToBackAds)
            if (toAdQueue.isNotEmpty() && rng.nextInt(100) < 65) {
                outroLeadIn = toAdQueue.next()
                outroLeadInRole = "TO_AD"
            }
            val adCount = if (config.allowsBackToBackAds && rng.nextInt(100) < 30 && advertQueue.size() >= 2) 2 else 1
            repeat(adCount) { i ->
                if (advertQueue.isNotEmpty()) {
                    standalone.add(StandaloneBreakClip(advertQueue.next(), RadioSegmentType.Commercial, "AD_${i + 1}"))
                }
            }
        }

        // Return Station ID (~90% probability)
        if (standalone.isNotEmpty() && idQueue.isNotEmpty() && rng.nextInt(100) < 90) {
            standalone.add(StandaloneBreakClip(idQueue.next(), RadioSegmentType.Announcer, "RETURN_ID"))
        }

        return Gen3BroadcastEvent.BreakBlockEvent(
            outroDryLeadIn = outroLeadIn,
            outroLeadInRole = outroLeadInRole,
            standaloneClips = standalone
        )
    }

    @Synchronized
    fun pickNextEvent(
        pickIntro: (TSong) -> TClip? = { null },
        pickFallbackIntro: () -> TClip? = { null }
    ): Gen3BroadcastEvent<TSong, TClip> {
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

        // Mid-Sweep Interlude (~25% between songs)
        if (currentPhase == Gen3DirectorPhase.MUSIC_SWEEP && lastSegmentType == RadioSegmentType.Song && consecutiveSongs >= 1) {
            if (rng.nextInt(100) < 25) {
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
            throw IllegalStateException("Rpf7RadioDirector[$stationName]: songQueue is empty")
        }
        val song = songQueue.next()

        // Intro Overlay selection:
        // 1. First try song-specific intro (~70%)
        var intro: TClip? = null
        var isFallback = false
        if (!config.isTalkRadio) {
            if (rng.nextInt(100) < 70) {
                intro = pickIntro(song)
            }
            // 2. If no song-specific intro, ~50% chance to roll dry general/time fallback intro
            if (intro == null && rng.nextInt(100) < 50) {
                val fb = pickFallbackIntro()
                if (fb != null) {
                    intro = fb
                    isFallback = true
                }
            }
        }

        songsRemainingInSweep--
        consecutiveSongs++
        lastSegmentType = RadioSegmentType.Song
        nextPlannedAction = "PLAY_SONG (remainingInSweep=$songsRemainingInSweep, intro=${intro != null}, fallback=$isFallback)"
        logSnapshot()
        return Gen3BroadcastEvent.SongEvent(song, intro, isFallbackIntroOverlay = isFallback)
    }

    private fun pickMidSweepInterlude(): Gen3BroadcastEvent.InterludeEvent<TClip>? {
        val hasSolo = soloQueue.isNotEmpty()
        val hasId = idQueue.isNotEmpty()
        val hasDj = timeOfDayDjQueue.isNotEmpty() || generalDjQueue.isNotEmpty()
        if (!hasSolo && !hasId && !hasDj) return null

        val roll = rng.nextInt(100)
        return when {
            hasSolo && roll < 40 -> {
                // Standalone DJ solo monologue (System 2 only!)
                Gen3BroadcastEvent.InterludeEvent(soloQueue.next(), RadioSegmentType.DJChatter, "INTERLUDE_SOLO", isDryOverlayEligible = false)
            }
            hasId && roll < 75 -> {
                // Standalone Station ID jingle (System 2 only!)
                Gen3BroadcastEvent.InterludeEvent(idQueue.next(), RadioSegmentType.Announcer, "INTERLUDE_ID", isDryOverlayEligible = false)
            }
            hasDj -> {
                // Dry spoken banter (System 1 overlay eligible if short!)
                val clip = if (timeOfDayDjQueue.isNotEmpty()) timeOfDayDjQueue.next() else generalDjQueue.next()
                Gen3BroadcastEvent.InterludeEvent(clip, RadioSegmentType.DJChatter, "INTERLUDE_CHATTER", isDryOverlayEligible = true)
            }
            hasId -> {
                Gen3BroadcastEvent.InterludeEvent(idQueue.next(), RadioSegmentType.Announcer, "INTERLUDE_ID", isDryOverlayEligible = false)
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
            "Rpf7[$stationName]: phase=$currentPhase, sweepRemaining=$songsRemainingInSweep/$currentSweepLength, action=$nextPlannedAction"
        }
    }

    fun resetAdverts(items: List<TClip>, trackKeyExtractor: (TClip) -> String) {
        val eligible = items.filter { !isTvAdvertStem(trackKeyExtractor(it)) }
        advertQueue.reset(eligible)
    }

    companion object {
        /**
         * Returns true for ALL non-radio TV content (TV commercials AND full ~12-min TV shows
         * such as mono_tv_ad_*, mono_tv_prb_*, mono_tv_mrhd_*, mono_tv_rsr_*, mono_tv_bright_*)
         * that must be excluded from FM radio commercial breaks.
         */
        fun isTvAdvertStem(nameOrStem: String): Boolean {
            val stem = nameOrStem.lowercase(Locale.ROOT).removeSuffix(".awc")
            return stem.startsWith("mono_tv_") ||
                stem.startsWith("tv_") ||
                stem.contains("_tv_")
        }
    }
}
