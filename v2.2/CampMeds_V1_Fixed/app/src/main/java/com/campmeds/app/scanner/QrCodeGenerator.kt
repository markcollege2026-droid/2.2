package com.campmeds.app.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Renders a QR payload as a printable QR bitmap, for the label staff attach to the physical
 * bag/bottle (spec section 4, screen 4: "generates/prints QR code").
 */
object QrCodeGenerator {
    fun generate(content: String, sizePx: Int = 512): Bitmap {
        // QRCodeWriter keeps ZXing's default 4-module quiet zone inside the matrix, which scanners need.
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val row = y * sizePx
            for (x in 0 until sizePx) {
                pixels[row + x] = if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }
}

/**
 * A QR code with human-readable text underneath (patient name, medication name). This exact bitmap is what
 * is previewed, printed, saved and shared, so what staff see is what comes out of the printer. Pure black on
 * pure white, large quiet zone, and the text sits clear of the code so it never reduces scannability.
 */
object QrLabel {
    private const val PADDING = 80

    fun render(content: String, lines: List<String>, qrSizePx: Int = 800): Bitmap {
        val width = qrSizePx + PADDING * 2
        val qr = QrCodeGenerator.generate(content, qrSizePx)

        val layouts = lines.filter { it.isNotBlank() }.mapIndexed { index, text ->
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = if (index == 0) 56f else 46f
                typeface = if (index == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width - PADDING * 2)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(3)
                .build()
        }
        val gap = 24
        val textHeight = layouts.sumOf { it.height + gap }
        val height = PADDING + qrSizePx + gap + textHeight + PADDING

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(qr, PADDING.toFloat(), PADDING.toFloat(), null)
        var y = (PADDING + qrSizePx + gap).toFloat()
        for (layout in layouts) {
            canvas.save()
            canvas.translate(PADDING.toFloat(), y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + gap
        }
        return bitmap
    }
}
