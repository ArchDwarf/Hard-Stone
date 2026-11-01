package com.hypixelminer

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.Calendar

class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }
    private val store by lazy { SnapshotStore(this) }

    private lateinit var content: LinearLayout
    private lateinit var subtitle: TextView
    private lateinit var refreshBtn: Button
    private lateinit var settingsCard: LinearLayout
    private lateinit var nickInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var statusText: TextView
    private lateinit var profileSpinner: Spinner
    private lateinit var dataBox: LinearLayout

    private var profiles: List<SkyProfile> = emptyList()
    private var uuid: String = ""
    private var loading = false
    private var ignoreSpinner = false

    // ── состояние графика
    private lateinit var scroll: ScrollView
    private var chartCard: LinearLayout? = null
    private var chartView: ChartView? = null
    private var chartTitle: TextView? = null
    private val rangeChips = mutableListOf<Pair<Long, TextView>>()
    private var chartKey: String? = null          // null = всего, иначе ключ ресурса
    private var chartRange: Long = 0L             // 0 = вся история
    private var chartHistory: List<Snapshot> = emptyList()
    private var chartChecked: Long = 0L

    // ─────────────────────────── жизненный цикл ───────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        nickInput.setText(prefs.getString("nick", ""))
        keyInput.setText(prefs.getString("key", ""))
        subtitle.text = prefs.getString("realName", null) ?: "SkyBlock · добытые блоки"

        if (nickInput.text.isBlank() || keyInput.text.isBlank()) {
            settingsCard.visibility = View.VISIBLE
            showStatus("Введи свой ник и API-ключ Hypixel, чтобы начать.", Theme.MUTED)
        } else {
            settingsCard.visibility = View.GONE
            refresh()
        }
    }

    // ─────────────────────────── загрузка данных ───────────────────────────

    private fun refresh() {
        if (loading) return
        val nick = nickInput.text.toString().trim()
        val key = keyInput.text.toString().trim()
        if (nick.isEmpty() || key.isEmpty()) {
            settingsCard.visibility = View.VISIBLE
            showStatus("Заполни ник и API-ключ.", Theme.WARN)
            return
        }
        loading = true
        refreshBtn.isEnabled = false
        refreshBtn.text = "Загрузка…"
        showStatus("Загружаю данные с Hypixel…", Theme.MUTED)

        Thread {
            try {
                // UUID кэшируем, пока ник не поменялся
                val cached = prefs.getString("uuid", null)
                val sameNick = nick.equals(prefs.getString("uuidFor", ""), ignoreCase = true)
                val (id, realName) = if (cached != null && sameNick) {
                    cached to (prefs.getString("realName", nick) ?: nick)
                } else {
                    val (u, n) = HypixelApi.lookupUuid(nick)
                    prefs.edit()
                        .putString("uuid", u).putString("uuidFor", nick)
                        .putString("realName", n).remove("profileId").apply()
                    u to n
                }
                val loaded = HypixelApi.loadProfiles(id, key)
                runOnUiThread { onLoaded(id, realName, loaded) }
            } catch (e: ApiException) {
                runOnUiThread { onFailed(e.message ?: "Ошибка") }
            } catch (e: Exception) {
                runOnUiThread { onFailed("Не удалось разобрать ответ сервера: ${e.message}") }
            }
        }.start()
    }

    private fun onLoaded(id: String, realName: String, loaded: List<SkyProfile>) {
        loading = false
        refreshBtn.isEnabled = true
        refreshBtn.text = "Обновить"
        uuid = id
        profiles = loaded
        subtitle.text = "$realName · SkyBlock"

        val savedId = prefs.getString("profileId", null)
        val index = loaded.indexOfFirst { it.id == savedId }.takeIf { it >= 0 }
            ?: loaded.indexOfFirst { it.selected }.takeIf { it >= 0 }
            ?: 0

        ignoreSpinner = true
        profileSpinner.adapter = darkAdapter(loaded.map { it.name + if (it.selected) "  (активный)" else "" })
        profileSpinner.setSelection(index)
        profileSpinner.visibility = if (loaded.size > 1) View.VISIBLE else View.GONE
        profileSpinner.post { ignoreSpinner = false }

        showProfile(loaded[index])
    }

    private fun onFailed(message: String) {
        loading = false
        refreshBtn.isEnabled = true
        refreshBtn.text = "Обновить"
        showStatus(message, Theme.ERROR)
    }

    // ─────────────────────────── отображение ───────────────────────────

    private fun showProfile(p: SkyProfile) {
        prefs.edit().putString("profileId", p.id).apply()
        dataBox.removeAllViews()

        val counts = p.collections
        if (counts == null) {
            showStatus(
                "Коллекции этого профиля скрыты. В игре открой SkyBlock Menu → Settings → " +
                    "API Settings и включи «Collections». Через пару минут нажми «Обновить».",
                Theme.WARN
            )
            addPowder(p)
            return
        }

        val now = System.currentTimeMillis()
        val full = MiningItems.ALL.associate { it.key to (counts[it.key] ?: 0L) }
        val history = store.add(uuid, p.id, full, now)
        statusText.visibility = View.GONE

        val total = full.values.sum()
        val today = SnapshotStore.baseline(history, startOfDay(0))
        val week = SnapshotStore.baseline(history, startOfDay(-6))
        val todayDelta = total - (today?.total ?: total)
        val weekDelta = total - (week?.total ?: total)

        // ── итог
        dataBox.addView(card().apply {
            addView(label("Всего добыто"))
            addView(text(Format.num(total), 40f, Theme.TEXT, bold = true).apply { setPadding(0, dp(2), 0, dp(10)) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(chip("${Format.delta(todayDelta)} сегодня", todayDelta > 0))
                addView(space(dp(8)))
                addView(chip("${Format.delta(weekDelta)} за 7 дней", weekDelta > 0))
            })
        })

        // ── график
        chartHistory = history
        chartChecked = store.lastChecked(uuid, p.id)
        rangeChips.clear()
        val chartBox = card().apply {
            val head = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val titleView = label("")
            chartTitle = titleView
            head.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(text("сбросить", 12f, Theme.ACCENT, bold = true).apply {
                setPadding(dp(8), dp(4), 0, dp(4))
                setOnClickListener { chartKey = null; updateChart() }
            })
            addView(head)

            val chipsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, dp(6))
            }
            listOf(DAY to "Сутки", 7 * DAY to "Неделя", 30 * DAY to "Месяц", 0L to "Всё").forEach { (range, title) ->
                val chip = text(title, 13f, Theme.TEXT, bold = true).apply {
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    setOnClickListener { chartRange = range; updateChart() }
                }
                rangeChips += range to chip
                chipsRow.addView(chip)
                chipsRow.addView(space(dp(6)))
            }
            addView(chipsRow)

            val cv = ChartView(context)
            chartView = cv
            addView(cv)
            addView(text(
                "Проведи пальцем по графику, чтобы увидеть точное значение. " +
                    "Нажми на ресурс в списке ниже, чтобы построить его график.",
                12f, Theme.MUTED
            ).apply { setPadding(0, dp(8), 0, 0) })
        }
        chartCard = chartBox
        dataBox.addView(chartBox)
        updateChart()

        // ── по ресурсам
        val rows = MiningItems.ALL.map { it to (full[it.key] ?: 0L) }.sortedByDescending { it.second }
        val nonZero = rows.filter { it.second > 0 }
        dataBox.addView(card().apply {
            addView(label("По ресурсам · прирост за сегодня"))
            addView(space(dp(6)))
            nonZero.forEachIndexed { i, (item, value) ->
                val d = value - (today?.counts?.get(item.key) ?: value)
                addView(resourceRow(item, value, d, maxOf(1L, nonZero.first().second)).apply {
                    isClickable = true
                    setOnClickListener {
                        chartKey = if (chartKey == item.key) null else item.key
                        updateChart()
                        chartCard?.let { c -> scroll.smoothScrollTo(0, dataBox.top + c.top - dp(8)) }
                    }
                })
                if (i < nonZero.lastIndex) addView(divider())
            }
            val zeros = rows.size - nonZero.size
            if (nonZero.isEmpty()) {
                addView(text("Пока ничего не добыто.", 14f, Theme.MUTED))
            } else if (zeros > 0) {
                addView(text("Ещё $zeros ${plural(zeros)} пока на нуле", 12f, Theme.MUTED).apply {
                    setPadding(0, dp(10), 0, 0)
                })
            }
        })

        addPowder(p)

        dataBox.addView(text(
            "Проверено: ${Format.time(now)} · снимков в истории: ${history.size}\n" +
                "Числа — это коллекции SkyBlock: сколько предметов собрано добычей. " +
                "Hypixel обновляет их с задержкой в несколько минут.",
            12f, Theme.MUTED
        ).apply { setPadding(dp(4), dp(4), dp(4), dp(24)) })
    }

    private fun updateChart() {
        val cv = chartView ?: return
        val item = MiningItems.ALL.find { it.key == chartKey }
        val series = chartHistory
            .map { it.time to (if (item == null) it.total else it.counts[item.key] ?: 0L) }
            .toMutableList()
        // после последнего изменения значение держалось до последней проверки
        if (series.isNotEmpty() && chartChecked > series.last().first) {
            series += chartChecked to series.last().second
        }
        val from = if (chartRange == 0L) null else System.currentTimeMillis() - chartRange
        cv.setData(series, item?.color ?: Theme.ACCENT, from)

        chartTitle?.text = "ГРАФИК · " + (item?.title ?: "всего добыто").uppercase()
        chartTitle?.setTextColor(if (item == null) Theme.MUTED else item.color)
        rangeChips.forEach { (range, chip) ->
            val on = range == chartRange
            chip.setTextColor(if (on) Theme.BG else Theme.TEXT)
            chip.background = rounded(if (on) Theme.ACCENT else Theme.LINE, 20)
        }
    }

    private fun addPowder(p: SkyProfile) {
        if (p.powders.isEmpty()) return
        dataBox.addView(card().apply {
            addView(label("Порошок Heart of the Mountain"))
            addView(space(dp(6)))
            p.powders.forEachIndexed { i, pw ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, dp(8))
                    addView(text(pw.title, 15f, Theme.TEXT), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.END
                        addView(text(Format.num(pw.current), 15f, Theme.TEXT, bold = true).apply { gravity = Gravity.END })
                        addView(text("всего ${Format.num(pw.total)}", 12f, Theme.MUTED).apply { gravity = Gravity.END })
                    })
                })
                if (i < p.powders.lastIndex) addView(divider())
            }
        })
    }

    // ─────────────────────────── построение интерфейса ───────────────────────────

    private fun buildUi() {
        scroll = ScrollView(this).apply {
            setBackgroundColor(Theme.BG)
            isFillViewport = true
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(content)
        setContentView(scroll)

        // Отступы под системные панели (Android 15 рисует приложение до краёв экрана)
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            content.setPadding(
                dp(16) + insets.systemWindowInsetLeft,
                dp(16) + insets.systemWindowInsetTop,
                dp(16) + insets.systemWindowInsetRight,
                dp(16) + insets.systemWindowInsetBottom
            )
            insets
        }

        // ── шапка
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text("Шахтёр Hypixel", 24f, Theme.TEXT, bold = true))
        subtitle = text("", 13f, Theme.MUTED)
        titles.addView(subtitle)
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))

        refreshBtn = button("Обновить", primary = true) { refresh() }
        header.addView(refreshBtn)
        header.addView(space(dp(8)))
        header.addView(button("⚙", primary = false) {
            settingsCard.visibility =
                if (settingsCard.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        content.addView(header)

        // ── настройки
        settingsCard = card().apply {
            addView(label("Настройки"))
            addView(space(dp(8)))
            nickInput = input("Ник в Minecraft", InputType.TYPE_CLASS_TEXT)
            addView(nickInput)
            addView(space(dp(8)))
            keyInput = input(
                "API-ключ Hypixel",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            )
            addView(keyInput)
            addView(text(
                "Ключ бесплатный: зайди на developer.hypixel.net, войди через аккаунт Hypixel " +
                    "и создай ключ. Он хранится только на этом телефоне.\n" +
                    "В игре должен быть включён доступ к коллекциям: SkyBlock Menu → Settings → API Settings.",
                12f, Theme.MUTED
            ).apply {
                setPadding(0, dp(10), 0, dp(12))
                setTextIsSelectable(true)
            })
            addView(button("Сохранить и загрузить", primary = true) {
                val nick = nickInput.text.toString().trim()
                prefs.edit()
                    .putString("nick", nick)
                    .putString("key", keyInput.text.toString().trim())
                    .apply()
                hideKeyboard()
                settingsCard.visibility = View.GONE
                refresh()
            }, LinearLayout.LayoutParams(-1, -2))
        }
        content.addView(settingsCard)

        // ── статус
        statusText = text("", 14f, Theme.MUTED).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Theme.CARD, 12)
            visibility = View.GONE
        }
        content.addView(statusText, marginBottom(12))

        // ── выбор профиля
        profileSpinner = Spinner(this).apply {
            background = rounded(Theme.CARD, 12)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            visibility = View.GONE
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    if (ignoreSpinner || pos !in profiles.indices) return
                    showProfile(profiles[pos])
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        content.addView(profileSpinner, marginBottom(12))

        dataBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(dataBox)
    }

    private fun resourceRow(item: MiningItem, value: Long, delta: Long, max: Long): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(9), 0, dp(9))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(View(this).apply { background = rounded(item.color, 3) }, LinearLayout.LayoutParams(dp(12), dp(12)))
        top.addView(space(dp(10)))
        top.addView(text(item.title, 15f, Theme.TEXT), LinearLayout.LayoutParams(0, -2, 1f))
        if (delta != 0L) {
            top.addView(text(Format.delta(delta), 12f, if (delta > 0) Theme.ACCENT else Theme.ERROR).apply {
                setPadding(0, 0, dp(10), 0)
            })
        }
        top.addView(text(Format.num(value), 15f, Theme.TEXT, bold = true).apply {
            fontFeatureSettings = "tnum"
        })
        row.addView(top)

        // полоска доли относительно самого большого ресурса
        val bar = LinearLayout(this).apply {
            background = rounded(Theme.LINE, 2)
            setPadding(0, dp(6), 0, 0)
        }
        val frac = (value.toDouble() / max).toFloat().coerceIn(0.02f, 1f)
        bar.addView(View(this).apply { background = rounded(item.color, 2) }, LinearLayout.LayoutParams(0, dp(4), frac))
        bar.addView(View(this), LinearLayout.LayoutParams(0, dp(4), 1f - frac))
        row.addView(bar, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(8) })
        return row
    }

    // ─────────────────────────── мелкие помощники ───────────────────────────

    private fun showStatus(message: String, color: Int) {
        statusText.text = message
        statusText.setTextColor(color)
        statusText.visibility = View.VISIBLE
    }

    private fun startOfDay(offsetDays: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, offsetDays)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun plural(n: Int): String {
        val m10 = n % 10; val m100 = n % 100
        return when {
            m10 == 1 && m100 != 11 -> "коллекция"
            m10 in 2..4 && m100 !in 12..14 -> "коллекции"
            else -> "коллекций"
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Theme.CARD, 16)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = marginBottom(12)
    }

    private fun marginBottom(m: Int) =
        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(m) }

    private fun text(s: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun label(s: String) = text(s.uppercase(), 11f, Theme.MUTED, bold = true).apply {
        letterSpacing = 0.08f
    }

    private fun chip(s: String, positive: Boolean) = text(s, 13f, if (positive) Theme.ACCENT else Theme.MUTED, bold = true).apply {
        background = rounded(if (positive) Theme.ACCENT_DIM else Theme.LINE, 20)
        setPadding(dp(12), dp(6), dp(12), dp(6))
    }

    private fun space(w: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(w, w) }

    private fun divider() = View(this).apply {
        setBackgroundColor(Theme.LINE)
        layoutParams = LinearLayout.LayoutParams(-1, maxOf(1, dp(1) / 2))
    }

    private fun button(s: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (primary) Theme.BG else Theme.TEXT)
        background = rounded(if (primary) Theme.ACCENT else Theme.CARD, 12)
        stateListAnimator = null
        minHeight = dp(42); minimumHeight = dp(42)
        minWidth = dp(42); minimumWidth = dp(42)
        setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { onClick() }
    }

    private fun input(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint
        inputType = type
        isSingleLine = true
        setTextColor(Theme.TEXT)
        setHintTextColor(Theme.MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        background = rounded(Theme.BG, 10)
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    private fun darkAdapter(items: List<String>) =
        object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(Theme.TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    setTextColor(Theme.TEXT); setBackgroundColor(Theme.CARD)
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                }
        }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    companion object {
        private const val DAY = 24 * 60 * 60 * 1000L
    }
}
