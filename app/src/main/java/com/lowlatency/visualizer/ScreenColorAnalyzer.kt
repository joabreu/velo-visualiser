package com.lowlatency.visualizer

import android.media.Image
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Extracts eight perimeter colour zones from an RGBA_8888 display frame.
 */
class ScreenColorAnalyzer {
    companion object {
        private const val EDGE = 0.18f
        private const val SAMPLES = 4
    }

    private val output = FloatArray(ScreenSyncBus.COMPONENTS)

    fun analyse(image: Image): FloatArray {
        output.fill(0f)

        if (image.planes.isEmpty()) return output

        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        if (pixelStride < 4) return output

        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return output

        sample(buffer, width, height, pixelStride, rowStride,
            0, 0f, 0f, EDGE, EDGE)
        sample(buffer, width, height, pixelStride, rowStride,
            1, EDGE, 0f, 1f - EDGE, EDGE)
        sample(buffer, width, height, pixelStride, rowStride,
            2, 1f - EDGE, 0f, 1f, EDGE)
        sample(buffer, width, height, pixelStride, rowStride,
            3, 1f - EDGE, EDGE, 1f, 1f - EDGE)
        sample(buffer, width, height, pixelStride, rowStride,
            4, 1f - EDGE, 1f - EDGE, 1f, 1f)
        sample(buffer, width, height, pixelStride, rowStride,
            5, EDGE, 1f - EDGE, 1f - EDGE, 1f)
        sample(buffer, width, height, pixelStride, rowStride,
            6, 0f, 1f - EDGE, EDGE, 1f)
        sample(buffer, width, height, pixelStride, rowStride,
            7, 0f, EDGE, EDGE, 1f - EDGE)

        return output
    }

    private fun sample(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        pixelStride: Int,
        rowStride: Int,
        zone: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        val x0 = (left * width).toInt().coerceIn(0, width - 1)
        val y0 = (top * height).toInt().coerceIn(0, height - 1)
        val x1 = (right * width).toInt().coerceIn(x0 + 1, width)
        val y1 = (bottom * height).toInt().coerceIn(y0 + 1, height)

        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0

        for (sy in 0 until SAMPLES) {
            val y = min(
                height - 1,
                y0 + ((y1 - y0) * (sy + 0.5f) / SAMPLES).toInt()
            )

            for (sx in 0 until SAMPLES) {
                val x = min(
                    width - 1,
                    x0 + ((x1 - x0) * (sx + 0.5f) / SAMPLES).toInt()
                )

                val offset = y * rowStride + x * pixelStride
                if (offset < 0 || offset + 3 >= buffer.limit()) continue

                r += buffer.get(offset).toInt() and 0xff
                g += buffer.get(offset + 1).toInt() and 0xff
                b += buffer.get(offset + 2).toInt() and 0xff
                count++
            }
        }

        if (count > 0) {
            val scale = 1f / (count * 255f)
            output[zone * 3] = r * scale
            output[zone * 3 + 1] = g * scale
            output[zone * 3 + 2] = b * scale
        }
    }
}
