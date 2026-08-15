package com.lowlatency.visualizer

/**
 * Latest perimeter colour frame captured from the Android display.
 *
 * Zones:
 *
 *       0  1  2
 *       7     3
 *       6  5  4
 */
object ScreenSyncBus {
    const val ZONES = 8
    const val COMPONENTS = ZONES * 3

    @Volatile
    private var latest: FloatArray? = null

    @Volatile
    var active: Boolean = false
        private set

    fun publish(rgb: FloatArray) {
        latest = rgb.copyOf()
        active = true
        CaptureHealth.markScreen()
    }

    fun snapshotInto(out: FloatArray): Boolean {
        val frame = latest ?: return false
        if (out.size < COMPONENTS) return false
        System.arraycopy(frame, 0, out, 0, COMPONENTS)
        return true
    }

    fun clear() {
        latest = null
        active = false
    }
}
