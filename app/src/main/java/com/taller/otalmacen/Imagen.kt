package com.taller.otalmacen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri

/** Prepara la foto para el OCR: la endereza, la pasa a blanco y negro, sube el contraste y la nitidez. */
object Imagen {

    fun mejorar(ctx: Context, uri: Uri): Bitmap? {
        val cr = ctx.contentResolver

        val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, medidas) }
        if (medidas.outWidth <= 0 || medidas.outHeight <= 0) return null

        var muestra = 1
        while (maxOf(medidas.outWidth, medidas.outHeight) / muestra > 2200) muestra *= 2
        val opciones = BitmapFactory.Options().apply { inSampleSize = muestra }
        var bmp: Bitmap = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opciones) }
            ?: return null

        val orient = try {
            cr.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val grados = when (orient) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (grados != 0f) {
            val m = Matrix()
            m.postRotate(grados)
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }

        // Blanco y negro (escala de grises) con más contraste
        val gris = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix()
        cm.setSaturation(0f)
        val c = 1.35f
        val t = 128f * (1f - c)
        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        val paint = Paint()
        paint.colorFilter = ColorMatrixColorFilter(cm)
        Canvas(gris).drawBitmap(bmp, 0f, 0f, paint)

        // Nitidez moderada: 3 * centro - 0.5 * (arriba + abajo + izquierda + derecha)
        val w = gris.width
        val h = gris.height
        val src = IntArray(w * h)
        gris.getPixels(src, 0, w, 0, 0, w, h)
        val dst = src.copyOf()
        for (y in 1 until h - 1) {
            val fila = y * w
            for (x in 1 until w - 1) {
                val i = fila + x
                val centro = src[i] and 0xFF
                val suma = (src[i - 1] and 0xFF) + (src[i + 1] and 0xFF) +
                    (src[i - w] and 0xFF) + (src[i + w] and 0xFF)
                var v = (6 * centro - suma) / 2
                if (v < 0) v = 0
                if (v > 255) v = 255
                dst[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        val resultado = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        resultado.setPixels(dst, 0, w, 0, 0, w, h)
        return resultado
    }
}
