package com.sc.gtradio.media.core

data class DjMarkerWindow(
    val introStartMs: Long = -1L,
    val introEndMs: Long = -1L,
    val outroStartMs: Long = -1L,
    val outroEndMs: Long = -1L
) {
    val hasIntroWindow: Boolean get() = introEndMs > 0L && (introStartMs < 0L || introEndMs > introStartMs)
    val effectiveIntroStartMs: Long get() = if (introStartMs >= 0L) introStartMs else 0L
    val introDurationMs: Long get() = if (hasIntroWindow) (introEndMs - effectiveIntroStartMs).coerceAtLeast(0L) else 0L
    val hasOutroWindow: Boolean get() = outroStartMs > 0L
}
