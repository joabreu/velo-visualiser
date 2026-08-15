package com.lowlatency.visualizer

import android.media.Image
import kotlin.math.atan2

/**
 * Extracts eight spatial colour zones from the ENTIRE captured display.
 *
 * Every sampled pixel contributes to exactly one zone. This is important for
 * video players such as Stremio: the video normally occupies the centre of
 * the display, so sampling only the outer edge misses most of the picture.
 *
 * The eight zones are angular sectors around the centre of the screen, keeping
 * the mapping useful for lights arranged around a TV while still making the
 * complete screen contribute to the result.
 */
class ScreenColorAnalyzer {
    companion object {
        private const val X_SAMPLES = 24
        private const val Y_SAMPLES = 14
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
            val y = ((sy + 0.5f) * height / Y_SAMPLES)
                .toInt()
                .coerceIn(0, height - 1)

            for (sx in 0 until X_SAMPLES) {
                val x = ((sx + 0.5f) * width / X_SAMPLES)
                    .toInt()
                    .coerceIn(0, width - 1)

                val offset = y * rowStride + x * pixelStride
                if (offset < 0 || offset + 3 >= buffer.limit()) continue

                val zone = zoneFor(x, y, width, height)

                red[zone] += buffer.get(offset).toInt() and 0xff
                green[zone] += buffer.get(offset + 1).toInt() and 0xff
                blue[zone] += buffer.get(offset + 2).toInt() and 0xff
                count[zone]++
            }
        }

        for (zone in 0 until ZONES) {
            if (count[zone] == 0) continue

            val scale = 1f / (count[zone] * 255f)
            val p = zone * 3
            output[p] = red[zone] * scale
            output[p + 1] = green[zone] * scale
            output[p + 2] = blue[zone] * scale
        }

        return output
    }

    private fun zoneFor(x: Int, y: Int, width: Int, height: Int): Int {
        val nx = (x + 0.5f) / width - 0.5f
        val ny = (y + 0.5f) / height - 0.5f

        // +Y is downward. Zone 0 starts at the top and zones progress
        // clockwise around the display.
        var angle = atan2(nx.toDouble(), -ny.toDouble())
        if (angle < 0.0) angle += Math.PI * 2.0

        return ((angle / (Math.PI * 2.0)) * ZONES)
            .toInt()
            .coerceIn(0, ZONES - 1)
    }
}
