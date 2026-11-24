package com.sc.gtradio.media.core

enum class Gen3DirectorPhase {
    MUSIC_SWEEP,
    BREAK_BLOCK,
    INTERLUDE
}

data class Gen3DirectorSnapshot(
    val phase: Gen3DirectorPhase,
    val songsRemainingInSweep: Int,
    val currentSweepLength: Int,
    val consecutiveSongs: Int,
    val pendingBreakClips: Int,
    val lastAction: String
)

data class StandaloneBreakClip<Clip>(
    val clip: Clip,
    val segmentType: RadioSegmentType,
    val role: String
)

sealed class Gen3BroadcastEvent<out Song, out Clip> {
    data class SongEvent<Song, Clip>(
        val song: Song,
        val songIntroOverlay: Clip? = null,
        val isFallbackIntroOverlay: Boolean = false
    ) : Gen3BroadcastEvent<Song, Clip>()

    data class BreakBlockEvent<Clip>(
        val outroDryLeadIn: Clip? = null,
        val outroLeadInRole: String? = null,
        val standaloneClips: List<StandaloneBreakClip<Clip>>
    ) : Gen3BroadcastEvent<Nothing, Clip>()

    data class InterludeEvent<Clip>(
        val clip: Clip,
        val segmentType: RadioSegmentType,
        val role: String,
        val isDryOverlayEligible: Boolean
    ) : Gen3BroadcastEvent<Nothing, Clip>()
}
