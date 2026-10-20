package com.teknoist.phaserprint.raster

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.teknoist.phaserprint.protocol.SplConstants
import java.io.File

/**
 * High-resolution PDF Rasterizer using Android's hardware-accelerated PdfRenderer at 600 DPI.
 */
class PdfRasterizer(
    private val paperType: Byte = SplConstants.PAPER_TYPE_A4,
    private val resolutionDpi: Int = 600
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

    /**
     * Renders all pages of a PDF into 1-bit monochrome byte arrays.
     * Invokes progressCallback for each page.
     */
    fun rasterizePdf(
        pdfFile: File,
        onProgress: (pageIndex: Int, totalPages: Int) -> Unit
    ): List<ByteArray> {
        val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val totalPages = renderer.pageCount
        val pagesBitmaps = mutableListOf<ByteArray>()

        try {
            for (pageIndex in 0 until totalPages) {
                onProgress(pageIndex + 1, totalPages)
                val page = renderer.openPage(pageIndex)

                // Android PdfRenderer default points is 72 DPI.
                // Scale factor from 72 DPI to 600 DPI: 600 / 72 = 8.3333f
                val scale = resolutionDpi.toFloat() / 72f
                val renderWidth = (page.width * scale).toInt()
                val renderHeight = (page.height * scale).toInt()

                // Create full target bitmap filled with white background
                val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)

                // Scale and center page onto print canvas
                val matrix = Matrix()
                matrix.postScale(scale, scale)

                // Center page if dimensions differ slightly
                val dx = (targetWidth - renderWidth) / 2f
                val dy = (targetHeight - renderHeight) / 2f
                if (dx > 0 || dy > 0) {
                    matrix.postTranslate(maxOf(0f, dx), maxOf(0f, dy))
                }

                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                page.close()

                // Dither to 1-bit monochrome
                val dithered = DitherEngine.dither(
                    bitmap = bitmap,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    mode = DitherEngine.DitherMode.FLOYD_STEINBERG
                )
                bitmap.recycle()
                pagesBitmaps.add(dithered)
            }
        } finally {
            renderer.close()
            pfd.close()
        }

        return pagesBitmaps
    }
}
