package com.lowlatency.visualizer

/**
 * Audio analysis produced by AudioCaptureService from AudioPlaybackCapture.
 * The six bands and spectral flux preserve substantially more musical detail
 * than the old low/mid/high-only representation.
 */
object AudioSyncBus {
    @Volatile var low: Float = 0f
    @Volatile var mid: Float = 0f
    @Volatile var high: Float = 0f
    @Volatile var band0: Float = 0f
    @Volatile var band1: Float = 0f
    @Volatile var band2: Float = 0f
    @Volatile var band3: Float = 0f
    @Volatile var band4: Float = 0f
    @Volatile var band5: Float = 0f
    @Volatile var spectralFlux: Float = 0f
    @Volatile var level: Float = 0f
    @Volatile var bassRatio: Float = 0.5f
    @Volatile var loudness: Float = 0f
    @Volatile var beatCount: Int = 0
    @Volatile var lastAnalysisNs: Long = 0L

    fun reset() {
        low = 0f
        mid = 0f
        high = 0f
        band0 = 0f
        band1 = 0f
        band2 = 0f
        band3 = 0f
        band4 = 0f
        band5 = 0f
        spectralFlux = 0f
        level = 0f
        bassRatio = 0.5f
        loudness = 0f
        beatCount = 0
        lastAnalysisNs = 0L
    }
}
