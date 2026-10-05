package com.tharos.allappsondeck

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withTranslation

class FolderIconView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#40FFFFFF".toColorInt() // Semi-transparent white
    }
    
    private val bgRect = RectF()
    private val icons = mutableListOf<Drawable>()

    fun setIcons(newIcons: List<Drawable>) {
        icons.clear()
        // Use newDrawable() to create an independent drawable wrapper instance for the folder preview.
        // This prevents FolderIconView's setBounds() calls from mutating the bounds of the same
        // drawable instances used in app item ViewHolders.
        icons.addAll(newIcons.take(4).map { it.constantState?.newDrawable() ?: it })
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bgRect.set(0f, 0f, w.toFloat(), h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Draw folder background circle
        canvas.drawOval(bgRect, bgPaint)

        if (icons.isEmpty()) return

        val padding = width * 0.15f
        val iconSize = (width - (padding * 3)) / 2f
        
        for (i in icons.indices) {
            val row = i / 2
            val col = i % 2
            
            val left = padding + col * (iconSize + padding)
            val top = padding + row * (iconSize + padding)
            
            val drawable = icons[i]
            val oldBounds = drawable.copyBounds()
            canvas.withTranslation(left, top) {
                val iconPx = iconSize.toInt()
                drawable.setBounds(0, 0, iconPx, iconPx)
                drawable.draw(canvas)
                drawable.bounds = oldBounds
            }
        }
    }
}
