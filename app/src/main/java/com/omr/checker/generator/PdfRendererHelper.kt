package com.omr.checker.generator

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object PdfRendererHelper {

    /**
     * Renders page 0 of a PDF file into a high-resolution Bitmap using Android's native PdfRenderer.
     * @param pdfFile The PDF file on disk
     * @param scaleMultiplier Scaling factor (e.g. 2.0f gives 1190 x 1684 px for crisp preview)
     */
    suspend fun renderPdfPageToBitmap(
        pdfFile: File,
        scaleMultiplier: Float = 2.0f
    ): Bitmap = withContext(Dispatchers.IO) {
        val fileDescriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val pdfRenderer = PdfRenderer(fileDescriptor)

        val page = pdfRenderer.openPage(0)
        val renderWidth = (page.width * scaleMultiplier).toInt()
        val renderHeight = (page.height * scaleMultiplier).toInt()

        val bitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
        // White canvas background
        bitmap.eraseColor(Color.WHITE)

        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

        page.close()
        pdfRenderer.close()
        fileDescriptor.close()

        bitmap
    }
}
