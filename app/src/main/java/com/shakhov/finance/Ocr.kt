package com.shakhov.finance

import android.content.Context
import android.graphics.*
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

object Ocr {
    private fun prepareData(ctx: Context): File {
        val dir = File(ctx.filesDir, "tess/tessdata"); dir.mkdirs()
        listOf("heb", "eng").forEach { l ->
            val f = File(dir, "$l.traineddata")
            if (!f.exists() || f.length() == 0L) ctx.assets.open("tessdata/$l.traineddata").use { i -> f.outputStream().use { i.copyTo(it) } }
        }
        return dir.parentFile!!
    }

    fun loadBitmap(ctx: Context, uri: Uri): Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > 3000) sample *= 2
        var bmp = ctx.contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }!!
        val rot = runCatching {
            ctx.contentResolver.openInputStream(uri)!!.use { ExifInterface(it).rotationDegrees }
        }.getOrDefault(0)
        if (rot != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        return bmp
    }

    /** Grayscale + contrast boost; thermal receipts are low-contrast. */
    private fun prep(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val cm = ColorMatrix().apply { setSaturation(0f) }
        val contrast = 1.6f; val t = (-0.5f * contrast + 0.5f) * 255f
        cm.postConcat(ColorMatrix(floatArrayOf(contrast, 0f, 0f, 0f, t, 0f, contrast, 0f, 0f, t, 0f, 0f, contrast, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        c.drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        return out
    }

    fun recognize(ctx: Context, uri: Uri): String {
        val base = prepareData(ctx)
        val api = TessBaseAPI()
        try {
            if (!api.init(base.absolutePath, "heb+eng")) error("OCR не запустился")
            api.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_COLUMN
            api.setImage(prep(loadBitmap(ctx, uri)))
            return api.utF8Text ?: ""
        } finally { api.recycle() }
    }
}
