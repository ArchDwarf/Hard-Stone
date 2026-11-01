package com.hypixelminer

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Подробный график: шкала значений и времени, точка на каждой проверке,
 * касание пальцем показывает точное значение, время и прирост.
 */
class ChartView(context: Context) : View(context) {

    private val d = resources.displayMetrics.density
    private val ru = Locale("ru")

    private var all: List<Pair<Long, Long>> = emptyList()
    private var vis: List<Pair<Long, Long>> = emptyList()
    private var domainMin = 0L
    private var domainMax = 0L
    private var color = Theme.ACCENT
    private var selected = -1

    // геометрия области построения (обновляется в onDraw)
    private val plot = RectF()
    private var yLo = 0.0
    private var yHi = 1.0

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.2f * d; style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.LINE; strokeWidth = 1f * d }
    private val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.MUTED; textSize = 10.5f * d }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.CARD }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.MUTED; strokeWidth = 1f * d
        pathEffect = DashPathEffect(floatArrayOf(4 * d, 4 * d), 0f)
    }
    private val tipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.BG }
    private val tipBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.LINE; style = Paint.Style.STROKE; strokeWidth = 1f * d
    }
    private val tipBig = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.TEXT; textSize = 14f * d; isFakeBoldText = true
    }
    private val tipSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.MUTED; textSize = 11.5f * d }
    private val tipDelta = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11.5f * d; isFakeBoldText = true }

    /**
     * @param points все точки (время → значение), по возрастанию времени
     * @param from начало периода или null для всей истории
     */
    fun setData(points: List<Pair<Long, Long>>, lineColor: Int, from: Long?) {
        all = points
        color = lineColor
        selected = -1
        linePaint.color = lineColor
        dotPaint.color = lineColor

        if (points.isEmpty()) {
            vis = emptyList()
        } else if (from == null) {
            vis = points
            domainMin = points.first().first
        } else {
            // берём последнюю точку до начала периода, чтобы линия начиналась с края
            val prev = points.indexOfLast { it.first <= from }
            vis = if (prev >= 0) points.subList(prev, points.size) else points
            domainMin = if (prev >= 0) from else points.first().first
        }
        if (vis.isNotEmpty()) domainMax = vis.last().first
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (250 * d).toInt())
    }

    // ───────────────────────── рисование ─────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inRange = vis.count { it.first >= domainMin }
        if (vis.size < 2 || domainMax <= domainMin || inRange < 1) {
            axisText.textAlign = Paint.Align.CENTER
            axisText.textSize = 13f * d
            val msg = if (all.size < 2) "График появится после второй проверки"
            else "За этот период пока мало проверок"
            canvas.drawText(msg, width / 2f, height / 2f, axisText)
            axisText.textSize = 10.5f * d
            return
        }

        // ── шкала значений с «круглыми» делениями
        val vMin = vis.minOf { it.second }.toDouble()
        val vMax = vis.maxOf { it.second }.toDouble()
        val raw = if (vMax - vMin < 1.0) max(1.0, vMax * 0.01) else vMax - vMin
        val step = niceStep(raw / 4)
        yLo = floor(vMin / step) * step
        yHi = ceil(vMax / step) * step
        if (yHi - yLo < step) yHi = yLo + step
        if (yLo < 0) yLo = 0.0

        val ticks = mutableListOf<Double>()
        var t = yLo
        while (t <= yHi + step / 2) { ticks += t; t += step }
        val labels = ticks.map { Format.compact(it.toLong()) }
        val labelW = labels.maxOf { axisText.measureText(it) }

        plot.set(labelW + 10 * d, 10 * d, width - 8 * d, height - 24 * d)

        axisText.textAlign = Paint.Align.RIGHT
        ticks.forEachIndexed { i, v ->
            val gy = y(v.toLong().toDouble())
            canvas.drawLine(plot.left, gy, plot.right, gy, gridPaint)
            canvas.drawText(labels[i], plot.left - 6 * d, gy + 3.5f * d, axisText)
        }

        // ── шкала времени
        val span = domainMax - domainMin
        val fmt = when {
            span < 36 * HOUR -> SimpleDateFormat("HH:mm", ru)
            span < 4 * DAY -> SimpleDateFormat("d MMM HH:mm", ru)
            else -> SimpleDateFormat("d MMM", ru)
        }
        val xTicks = 4
        for (i in 0..xTicks) {
            val tt = domainMin + span * i / xTicks
            val gx = x(tt)
            canvas.drawLine(gx, plot.bottom, gx, plot.bottom + 4 * d, gridPaint)
            axisText.textAlign = when (i) {
                0 -> Paint.Align.LEFT
                xTicks -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            canvas.drawText(fmt.format(Date(tt)), gx, height - 6 * d, axisText)
        }

        // ── линия и заливка (обрезаем по области построения)
        canvas.save()
        canvas.clipRect(plot.left, plot.top - 6 * d, plot.right + 6 * d, plot.bottom)
        val line = Path()
        vis.forEachIndexed { i, (tm, v) ->
            if (i == 0) line.moveTo(x(tm), y(v.toDouble())) else line.lineTo(x(tm), y(v.toDouble()))
        }
        val area = Path(line).apply {
            lineTo(x(vis.last().first), plot.bottom)
            lineTo(x(vis.first().first), plot.bottom)
            close()
        }
        fillPaint.shader = LinearGradient(
            0f, plot.top, 0f, plot.bottom,
            (color and 0x00FFFFFF) or 0x50000000, color and 0x00FFFFFF, Shader.TileMode.CLAMP
        )
        canvas.drawPath(area, fillPaint)
        canvas.drawPath(line, linePaint)

        // точки проверок (если их не слишком много)
        val visibleDots = vis.filter { it.first >= domainMin }
        if (visibleDots.size <= 80) {
            visibleDots.forEach { (tm, v) ->
                canvas.drawCircle(x(tm), y(v.toDouble()), 3.6f * d, ringPaint)
                canvas.drawCircle(x(tm), y(v.toDouble()), 2.4f * d, dotPaint)
            }
        }
        canvas.restore()

        // последняя точка крупнее
        val (lt, lv) = vis.last()
        canvas.drawCircle(x(lt), y(lv.toDouble()), 6f * d, ringPaint)
        canvas.drawCircle(x(lt), y(lv.toDouble()), 4.2f * d, dotPaint)

        if (selected in vis.indices) drawTooltip(canvas, selected)
    }

    private fun drawTooltip(canvas: Canvas, i: Int) {
        val (tm, v) = vis[i]
        val px = x(tm)
        val py = y(v.toDouble())

        canvas.drawLine(px, plot.top, px, plot.bottom, crossPaint)
        canvas.drawCircle(px, py, 7f * d, ringPaint)
        canvas.drawCircle(px, py, 5f * d, dotPaint)

        val prev = if (i > 0) vis[i - 1].second else null
        val line1 = Format.num(v)
        val line2 = SimpleDateFormat("d MMMM, HH:mm", ru).format(Date(tm))
        val delta = prev?.let { v - it }
        val line3 = delta?.let { "${Format.delta(it)} с прошлой проверки" }
        tipDelta.color = if ((delta ?: 0L) > 0L) Theme.ACCENT else Theme.MUTED

        val pad = 10 * d
        val w = maxOf(
            tipBig.measureText(line1), tipSmall.measureText(line2),
            line3?.let { tipDelta.measureText(it) } ?: 0f
        ) + pad * 2
        val h = pad * 2 + 16 * d + 15 * d + (if (line3 != null) 15 * d else 0f)

        var left = px + 12 * d
        if (left + w > width - 2 * d) left = px - 12 * d - w
        left = left.coerceAtLeast(2 * d)
        var top = py - h - 10 * d
        if (top < 2 * d) top = py + 10 * d
        top = top.coerceAtMost(height - h - 2 * d)

        val r = RectF(left, top, left + w, top + h)
        canvas.drawRoundRect(r, 10 * d, 10 * d, tipBg)
        canvas.drawRoundRect(r, 10 * d, 10 * d, tipBorder)
        var by = top + pad + 13 * d
        canvas.drawText(line1, left + pad, by, tipBig)
        by += 16 * d
        canvas.drawText(line2, left + pad, by, tipSmall)
        if (line3 != null) {
            by += 15 * d
            canvas.drawText(line3, left + pad, by, tipDelta)
        }
    }

    // ───────────────────────── касания ─────────────────────────

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (vis.size < 2) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                var best = -1
                var bestDist = Float.MAX_VALUE
                vis.forEachIndexed { i, (tm, _) ->
                    if (tm < domainMin) return@forEachIndexed
                    val dist = abs(x(tm) - e.x)
                    if (dist < bestDist) { bestDist = dist; best = i }
                }
                if (best != selected) { selected = best; invalidate() }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    // ───────────────────────── помощники ─────────────────────────

    private fun x(t: Long): Float =
        plot.left + ((t - domainMin).toDouble() / (domainMax - domainMin) * plot.width()).toFloat()

    private fun y(v: Double): Float =
        plot.bottom - ((v - yLo) / (yHi - yLo) * plot.height()).toFloat()

    private fun niceStep(rough: Double): Double {
        if (rough <= 0) return 1.0
        val mag = 10.0.pow(floor(log10(rough)))
        val n = rough / mag
        val nice = when {
            n <= 1 -> 1.0
            n <= 2 -> 2.0
            n <= 2.5 -> 2.5
            n <= 5 -> 5.0
            else -> 10.0
        }
        return max(1.0, nice * mag)
    }

    companion object {
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR
    }
}
