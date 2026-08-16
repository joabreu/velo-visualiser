package com.lowlatency.visualizer

import android.media.Image

/**
 * Extracts colour from the complete captured display using a 4 x 3 grid.
 *
 * Every grid cell is sampled across its full area. The centre of a Stremio
 * video therefore contributes just as much as the edges; this is deliberately
 * not a perimeter-only sampler.
 */
class ScreenColorAnalyzer {
    companion object {
        private const val X_SAMPLES = 32
        private const val Y_SAMPLES = 24
        private const val ZONES = ScreenSyncBus.ZONES
    }

    private val output = FloatArray(ScreenSyncBus.COMPONENTS)
    private val red = LongArray(ZONES)
    private val green = LongArray(ZONES)
    private val blue = LongArray(ZONES)
    private val count = IntArray(ZONES)

    fun analyse(image: Image): FloatArray {
        output.fill(0f)
        red.fill(0L)
        green.fill(0L)
        blue.fill(0L)
        count.fill(0)

        if (image.planes.isEmpty()) return output

        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height

        if (pixelStride < 4 || width <= 0 || height <= 0) return output

        for (sy in 0 until Y_SAMPLES) {
            val y = ((sy + 0.5f) * height / Y_SAMPLES).toInt()
                .coerceIn(0, height - 1)
            val rowBase = y * rowStride

            for (sx in 0 until X_SAMPLES) {
                val x = ((sx + 0.5f) * width / X_SAMPLES).toInt()
                    .coerceIn(0, width - 1)
                val offset = rowBase + x * pixelStride
                if (offset < 0 || offset + 3 >= buffer.limit()) continue

                val col = (sx * ScreenSyncBus.COLUMNS) / X_SAMPLES
                val row = (sy * ScreenSyncBus.ROWS) / Y_SAMPLES
                val zone = row * ScreenSyncBus.COLUMNS + col

                red[zone] += buffer.get(offset).toInt() and 0xff
                green[zone] += buffer.get(offset + 1).toInt() and 0xff
                blue[zone] += buffer.get(offset + 2).toInt() and 0xff
                count[zone]++
            }
        }

        for (zone in 0 until ZONES) {
            val n = count[zone]
            if (n == 0) continue
            val scale = 1f / (n * 255f)
            val p = zone * 3
            output[p] = red[zone] * scale
            output[p + 1] = green[zone] * scale
            output[p + 2] = blue[zone] * scale
        }

        return output
    }
}
