package com.teknoist.phaserprint.raster

import android.graphics.Bitmap

/**
 * High-performance 1-bit monochrome dithering engine for Android.
 * Converts 32-bit ARGB Bitmaps into packed 1-bit-per-pixel row-major byte arrays (MSB first).
 * In output byte array: 1 = black (laser deposit), 0 = white (paper background).
 */
object DitherEngine {

    enum class DitherMode {
        FLOYD_STEINBERG,
        THRESHOLD
    }

    /**
     * Dithers a Bitmap to 1-bit monochrome byte array.
     */
    fun dither(
        bitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        mode: DitherMode = DitherMode.FLOYD_STEINBERG,
        threshold: Int = 128
    ): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val lineWidthBytes = (targetWidth + 7) / 8
        val output = ByteArray(lineWidthBytes * targetHeight)

        // Extract grayscale values into a fast 1D integer buffer
        val grayPixels = IntArray(width * height)
        val argbPixels = IntArray(width)

        for (y in 0 until height) {
            bitmap.getPixels(argbPixels, 0, width, 0, y, width, 1)
            val rowOffset = y * width
            for (x in 0 until width) {
                val color = argbPixels[x]
                val a = (color ushr 24) and 0xFF
                if (a < 50) {
                    // Transparent pixels become white
                    grayPixels[rowOffset + x] = 255
                } else {
                    val r = (color ushr 16) and 0xFF
                    val g = (color ushr 8) and 0xFF
                    val b = color and 0xFF
                    // Rec. 601 luma formula
                    val luma = (r * 299 + g * 587 + b * 114) / 1000
                    grayPixels[rowOffset + x] = luma
                }
            }
        }

        if (mode == DitherMode.THRESHOLD) {
            // Simple fast thresholding
            for (y in 0 until minOf(height, targetHeight)) {
                val rowOffset = y * width
                val outRowOffset = y * lineWidthBytes
                var currentByte = 0
                var bitIndex = 7

                for (x in 0 until targetWidth) {
                    val isBlack = if (x < width) grayPixels[rowOffset + x] < threshold else false
                    if (isBlack) {
                        currentByte = currentByte or (1 shl bitIndex)
                    }
                    bitIndex--
                    if (bitIndex < 0) {
                        output[outRowOffset + (x shr 3)] = currentByte.toByte()
                        currentByte = 0
                        bitIndex = 7
                    }
                }
                if (bitIndex != 7) {
                    output[outRowOffset + (targetWidth shr 3)] = currentByte.toByte()
                }
            }
        } else {
            // Floyd-Steinberg error diffusion dithering
            // Error distribution:
            //       (x, y)   --> (x+1, y)   * 7/16
            // (x-1, y+1) * 3/16  (x, y+1) * 5/16  (x+1, y+1) * 1/16
            // Maintain error buffer for current row and next row
            val errCurr = FloatArray(width + 2)
            var errNext = FloatArray(width + 2)

            for (y in 0 until minOf(height, targetHeight)) {
                val rowOffset = y * width
                val outRowOffset = y * lineWidthBytes
                var currentByte = 0
                var bitIndex = 7

                for (x in 0 until targetWidth) {
                    val isBlack: Boolean
                    if (x < width) {
                        val oldVal = (grayPixels[rowOffset + x] + errCurr[x + 1]).coerceIn(0f, 255f)
                        val newVal = if (oldVal < threshold) 0f else 255f
                        val quantError = oldVal - newVal
                        isBlack = (newVal == 0f)

                        // Diffuse error
                        errCurr[x + 2] += quantError * (7f / 16f)
                        errNext[x] += quantError * (3f / 16f)
                        errNext[x + 1] += quantError * (5f / 16f)
                        errNext[x + 2] += quantError * (1f / 16f)
                    } else {
                        isBlack = false
                    }

                    if (isBlack) {
                        currentByte = currentByte or (1 shl bitIndex)
                    }
                    bitIndex--
                    if (bitIndex < 0) {
                        output[outRowOffset + (x shr 3)] = currentByte.toByte()
                        currentByte = 0
                        bitIndex = 7
                    }
                }
                if (bitIndex != 7) {
                    output[outRowOffset + (targetWidth shr 3)] = currentByte.toByte()
                }

                // Swap error buffers
                System.arraycopy(errNext, 0, errCurr, 0, errNext.size)
                errNext.fill(0f)
            }
        }

        return output
    }
}
