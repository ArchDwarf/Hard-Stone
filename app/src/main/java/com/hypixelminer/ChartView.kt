package com.hypixelminer

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** График общего количества добытых блоков по снимкам. */
class ChartView(context: Context) : View(context) {

    private var points: List<Pair<Long, Long>> = emptyList()
    private val d = resources.displayMetrics.density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.ACCENT; strokeWidth = 2.5f * d; style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.LINE; strokeWidth = 1f * d
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.MUTED; textSize = 11f * d
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.ACCENT }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.CARD }
    private val dateFmt = SimpleDateFormat("d MMM", Locale("ru"))

    fun setData(history: List<Snapshot>) {
        points = history.map { it.time to it.total }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (180 * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 4 * d
        val right = width - 4 * d
        val top = 16 * d
        val bottom = height - 22 * d

        if (points.size < 2) {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 13f * d
            canvas.drawText("График появится после второй проверки", width / 2f, height / 2f, textPaint)
            textPaint.textSize = 11f * d
            return
        }

        val minT = points.first().first
        val maxT = points.last().first
        val minV = points.minOf { it.second }
        val maxV = points.maxOf { it.second }
        val spanT = (maxT - minT).coerceAtLeast(1L).toFloat()
        val spanV = (maxV - minV).coerceAtLeast(1L).toFloat()

        fun x(t: Long) = left + (t - minT) / spanT * (right - left)
        fun y(v: Long) = bottom - (v - minV) / spanV * (bottom - top)

        // сетка: верх, середина, низ
        for (i in 0..2) {
            val gy = top + (bottom - top) * i / 2f
            canvas.drawLine(left, gy, right, gy, gridPaint)
        }

        val line = Path()
        points.forEachIndexed { i, (t, v) ->
            if (i == 0) line.moveTo(x(t), y(v)) else line.lineTo(x(t), y(v))
        }
        val area = Path(line).apply {
            lineTo(x(maxT), bottom); lineTo(x(minT), bottom); close()
        }
        fillPaint.shader = LinearGradient(
            0f, top, 0f, bottom,
            (Theme.ACCENT and 0x00FFFFFF) or 0x55000000, Theme.ACCENT and 0x00FFFFFF,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(area, fillPaint)
        canvas.drawPath(line, linePaint)

        val (lt, lv) = points.last()
        canvas.drawCircle(x(lt), y(lv), 5.5f * d, dotRing)
        canvas.drawCircle(x(lt), y(lv), 4f * d, dotPaint)

        // подписи
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(Format.num(maxV), left, top - 4 * d, textPaint)
        canvas.drawText(dateFmt.format(Date(minT)), left, height - 4 * d, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(dateFmt.format(Date(maxT)), right, height - 4 * d, textPaint)
        canvas.drawText(Format.num(minV), right, bottom - 4 * d, textPaint)
    }
}
