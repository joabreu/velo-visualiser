package com.lowlatency.visualizer

/**
 * Monotonic freshness markers for the background capture pipeline.
 * Kept independent of Activity/GL lifecycle so the foreground service can
 * prove that audio and video are still arriving after Velo is backgrounded.
 */
object CaptureHealth {
    private const val FRESH_NS = 750_000_000L

    @Volatile private var lastScreenNs = 0L
    @Volatile private var lastRawAudioNs = 0L
    @Volatile private var lastAudioAnalysisNs = 0L

    fun markScreen() { lastScreenNs = System.nanoTime() }
    fun markRawAudio() { lastRawAudioNs = System.nanoTime() }
    fun markAudioAnalysis() { lastAudioAnalysisNs = System.nanoTime() }

    fun screenFresh(nowNs: Long = System.nanoTime()): Boolean =
        age(lastScreenNs, nowNs) <= FRESH_NS

    fun rawAudioFresh(nowNs: Long = System.nanoTime()): Boolean =
        age(lastRawAudioNs, nowNs) <= FRESH_NS

    fun audioAnalysisFresh(nowNs: Long = System.nanoTime()): Boolean =
        age(lastAudioAnalysisNs, nowNs) <= FRESH_NS

    fun screenAgeMs(nowNs: Long = System.nanoTime()): Long = age(lastScreenNs, nowNs) / 1_000_000L
    fun rawAudioAgeMs(nowNs: Long = System.nanoTime()): Long = age(lastRawAudioNs, nowNs) / 1_000_000L
    fun audioAnalysisAgeMs(nowNs: Long = System.nanoTime()): Long = age(lastAudioAnalysisNs, nowNs) / 1_000_000L

    fun reset() {
        lastScreenNs = 0L
        lastRawAudioNs = 0L
        lastAudioAnalysisNs = 0L
    }

    private fun age(timestampNs: Long, nowNs: Long): Long =
        if (timestampNs == 0L) Long.MAX_VALUE else (nowNs - timestampNs).coerceAtLeast(0L)
}
