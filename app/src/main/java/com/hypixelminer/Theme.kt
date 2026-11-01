package com.hypixelminer

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Theme {
    const val BG = 0xFF12161F.toInt()
    const val CARD = 0xFF1C2230.toInt()
    const val LINE = 0xFF2A3242.toInt()
    const val TEXT = 0xFFE6EAF2.toInt()
    const val MUTED = 0xFF8B93A7.toInt()
    const val ACCENT = 0xFF3FD08A.toInt()
    const val ACCENT_DIM = 0xFF1E3A2F.toInt()
    const val WARN = 0xFFF2B84B.toInt()
    const val ERROR = 0xFFFF6B6B.toInt()
}

object Format {
    private val ru = Locale("ru")
    private val nf: NumberFormat = NumberFormat.getIntegerInstance(ru)
    private val timeFmt = SimpleDateFormat("d MMMM, HH:mm", ru)

    fun num(v: Long): String = nf.format(v).replace(' ', ' ')
    fun delta(v: Long): String = if (v > 0) "+" + num(v) else if (v < 0) "−" + num(-v) else "0"
    fun time(ms: Long): String = timeFmt.format(Date(ms))

    /** Короткая запись для подписей шкалы: 950, 12,5 тыс., 1,25 млн */
    fun compact(v: Long): String {
        val a = kotlin.math.abs(v)
        fun f(x: Double, suffix: String): String {
            val s = if (x >= 100 || x == Math.floor(x)) String.format(ru, "%.0f", x)
            else if (x >= 10) String.format(ru, "%.1f", x)
            else String.format(ru, "%.2f", x)
            val trimmed = if (s.contains(',') || s.contains('.')) s.trimEnd('0').trimEnd(',', '.') else s
            return "$trimmed $suffix"
        }
        return when {
            a >= 1_000_000_000 -> f(v / 1e9, "млрд")
            a >= 1_000_000 -> f(v / 1e6, "млн")
            a >= 10_000 -> f(v / 1e3, "тыс.")
            else -> num(v)
        }
    }
}
