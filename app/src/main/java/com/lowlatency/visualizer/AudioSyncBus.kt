package com.lowlatency.visualizer

/**
 * Audio analysis produced by AudioCaptureService from AudioPlaybackCapture.
 * Unlike the renderer's BeatBus, this bus is fed by the foreground service and
 * therefore remains live when the Activity/GL thread is gone.
 */
object AudioSyncBus {
    @Volatile var low: Float = 0f
    @Volatile var mid: Float = 0f
    @Volatile var high: Float = 0f
    @Volatile var level: Float = 0f
    @Volatile var bassRatio: Float = 0.5f
    @Volatile var loudness: Float = 0f
    @Volatile var beatCount: Int = 0
    @Volatile var lastAnalysisNs: Long = 0L

    fun reset() {
        low = 0f
        mid = 0f
        high = 0f
        level = 0f
        bassRatio = 0.5f
        loudness = 0f
        beatCount = 0
        lastAnalysisNs = 0L
    }
}
