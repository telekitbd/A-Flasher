package com.aflasher.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Round button with a drawn (font independent) play, edit or info icon, perfectly centered. */
class IconButton(ctx: Context, private val kind: Int, private val fill: Int) : View(ctx) {
    companion object {
        const val PLAY = 0
        const val EDIT = 1
        const val INFO = 2
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = minOf(w, h)
        val cx = w / 2f
        val cy = h / 2f
        p.style = Paint.Style.FILL
        p.color = fill
        c.drawCircle(cx, cy, s / 2f, p)
        p.color = Color.WHITE
        if (kind == PLAY) {
            val path = Path()
            path.moveTo(cx - 0.11f * s, cy - 0.19f * s)
            path.lineTo(cx - 0.11f * s, cy + 0.19f * s)
            path.lineTo(cx + 0.20f * s, cy)
            path.close()
            c.drawPath(path, p)
        } else if (kind == INFO) {
            c.drawCircle(cx, cy - 0.20f * s, 0.055f * s, p)
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            p.strokeWidth = 0.09f * s
            c.drawLine(cx, cy - 0.06f * s, cx, cy + 0.22f * s, p)
        } else {
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            p.strokeWidth = 0.075f * s
            c.drawLine(cx - 0.14f * s, cy + 0.10f * s, cx + 0.15f * s, cy - 0.19f * s, p)
            c.drawLine(cx - 0.17f * s, cy + 0.19f * s, cx - 0.02f * s, cy + 0.19f * s, p)
        }
    }
}
