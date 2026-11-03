package org.futo.inputmethod.latin.uix.theme.custom

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import kotlin.math.max

/**
 * Draws a photo so it fills the keyboard (cropping the edges instead of stretching), then lays
 * the theme's background colour over it so the keys stay readable. [dim] is how strongly.
 */
class PhotoBackgroundDrawable(
    private val bitmap: Bitmap,
    dimColor: Int,
    dim: Float,
) : Drawable() {
    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint().apply {
        color = ColorUtils.setAlphaComponent(dimColor, (dim.coerceIn(0f, 1f) * 255).toInt())
    }
    private val target = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty || bitmap.width == 0 || bitmap.height == 0) return

        val scale = max(b.width().toFloat() / bitmap.width, b.height().toFloat() / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        val left = b.left + (b.width() - w) / 2f
        val top = b.top + (b.height() - h) / 2f
        target.set(left, top, left + w, top + h)

        val saved = canvas.save()
        canvas.clipRect(b)
        canvas.drawBitmap(bitmap, null, target, photoPaint)
        canvas.restoreToCount(saved)
        canvas.drawRect(b, dimPaint)
    }

    override fun setAlpha(alpha: Int) {
        photoPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        photoPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
