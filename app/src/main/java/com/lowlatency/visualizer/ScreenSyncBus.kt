package com.lowlatency.visualizer

/**
 * Latest spatial colour frame captured from the complete Android display.
 *
 * The frame is divided into a 4 x 3 grid:
 *
 *       0  1  2  3
 *       4  5  6  7
 *       8  9 10 11
 */
object ScreenSyncBus {
    const val COLUMNS = 4
    const val ROWS = 3
    const val ZONES = COLUMNS * ROWS
    const val COMPONENTS = ZONES * 3

    @Volatile
    private var latest: FloatArray? = null

    @Volatile
    var active: Boolean = false
        private set

    fun publish(rgb: FloatArray) {
        if (rgb.size < COMPONENTS) return
        latest = rgb.copyOf(COMPONENTS)
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
