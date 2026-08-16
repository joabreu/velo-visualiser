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
    private val vividRed = FloatArray(ZONES)
    private val vividGreen = FloatArray(ZONES)
    private val vividBlue = FloatArray(ZONES)
    private val weight = FloatArray(ZONES)
    private val count = IntArray(ZONES)

    fun analyse(image: Image): FloatArray {
        output.fill(0f)
        red.fill(0L)
        green.fill(0L)
        blue.fill(0L)
        vividRed.fill(0f)
        vividGreen.fill(0f)
        vividBlue.fill(0f)
        weight.fill(0f)
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

                val r = buffer.get(offset).toInt() and 0xff
                val g = buffer.get(offset + 1).toInt() and 0xff
                val b = buffer.get(offset + 2).toInt() and 0xff

                red[zone] += r.toLong()
                green[zone] += g.toLong()
                blue[zone] += b.toLong()
                count[zone]++

                // A plain RGB average washes saturated video colours out when a
                // zone also contains black/white UI. Give chromatic pixels more
                // influence while retaining a neutral baseline for white/grey.
                val maxChannel = maxOf(r, g, b)
                val minChannel = minOf(r, g, b)
                val chroma = (maxChannel - minChannel) / 255f
                val chroma2 = chroma * chroma
                val sampleWeight = 1f + 5f * chroma2

                vividRed[zone] += r * sampleWeight
                vividGreen[zone] += g * sampleWeight
                vividBlue[zone] += b * sampleWeight
                weight[zone] += sampleWeight
            }
        }

        for (zone in 0 until ZONES) {
            val n = count[zone]
            if (n == 0) continue

            val normalScale = 1f / (n * 255f)
            val vividScale = 1f / (weight[zone] * 255f)

            val normalR = red[zone] * normalScale
            val normalG = green[zone] * normalScale
            val normalB = blue[zone] * normalScale

            val vividR = vividRed[zone] * vividScale
            val vividG = vividGreen[zone] * vividScale
            val vividB = vividBlue[zone] * vividScale

            // Keep genuinely neutral zones neutral. As chroma increases,
            // progressively favour the chroma-weighted colour.
            val avgMax = maxOf(normalR, normalG, normalB)
            val avgMin = minOf(normalR, normalG, normalB)
            val avgChroma = (avgMax - avgMin).coerceIn(0f, 1f)
            val vividMix = (avgChroma * 2.5f).coerceIn(0f, 0.82f)

            val p = zone * 3
            output[p] = (normalR * (1f - vividMix) + vividR * vividMix).coerceIn(0f, 1f)
            output[p + 1] = (normalG * (1f - vividMix) + vividG * vividMix).coerceIn(0f, 1f)
            output[p + 2] = (normalB * (1f - vividMix) + vividB * vividMix).coerceIn(0f, 1f)
        }

        return output
    }
}
