package com.teknoist.phaserprint.raster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import com.teknoist.phaserprint.protocol.SplConstants
import java.io.InputStream

/**
 * Image rasterizer for JPEG, PNG, WEBP, etc.
 * Proportionally scales images to fit within printable bounds with standard margins.
 */
class ImageRasterizer(
    private val context: Context,
    private val paperType: Byte = SplConstants.PAPER_TYPE_A4
) {
    val targetWidth: Int = if (paperType == SplConstants.PAPER_TYPE_A4) {
        SplConstants.PAGE_PIXEL_WIDTH_A4
    } else {
        SplConstants.PAGE_PIXEL_WIDTH_LETTER
    }

    val targetHeight: Int = if (paperType == SplConstants.PAPER_TYPE_A4) {
        SplConstants.PAGE_PIXEL_HEIGHT_A4
    } else {
        SplConstants.PAGE_PIXEL_HEIGHT_LETTER
    }

    fun rasterizeImage(imageUri: Uri): ByteArray {
        val inputStream: InputStream? = context.contentResolver.openInputStream(imageUri)
        val originalBitmap = BitmapFactory.decodeStream(inputStream)
            ?: throw IllegalArgumentException("Failed to decode image from $imageUri")
        inputStream?.close()

        val canvasBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        canvas.drawColor(Color.WHITE)

        // Fit within margins (e.g. 150 pixels margin on all sides)
        val margin = 200f
        val availWidth = targetWidth - 2 * margin
        val availHeight = targetHeight - 2 * margin

        val scale = minOf(availWidth / originalBitmap.width, availHeight / originalBitmap.height)
        val scaledW = originalBitmap.width * scale
        val scaledH = originalBitmap.height * scale

        val left = margin + (availWidth - scaledW) / 2f
        val top = margin + (availHeight - scaledH) / 2f
        val destRect = RectF(left, top, left + scaledW, top + scaledH)

        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(originalBitmap, null, destRect, paint)
        originalBitmap.recycle()

        val dithered = DitherEngine.dither(
            bitmap = canvasBitmap,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            mode = DitherEngine.DitherMode.FLOYD_STEINBERG
        )
        canvasBitmap.recycle()

        return dithered
    }
}
