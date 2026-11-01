package com.hypixelminer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Снимок коллекций в момент проверки. */
data class Snapshot(val time: Long, val counts: Map<String, Long>) {
    val total: Long get() = counts.values.sum()
}

/**
 * Хранит историю снимков на телефоне (отдельно для каждого игрока и профиля).
 * Новый снимок добавляется только когда что-то изменилось.
 */
class SnapshotStore(context: Context) {
    private val prefs = context.getSharedPreferences("history", Context.MODE_PRIVATE)

    private fun key(uuid: String, profileId: String) = "h_${uuid}_$profileId"

    fun load(uuid: String, profileId: String): List<Snapshot> {
        val raw = prefs.getString(key(uuid, profileId), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val c = o.getJSONObject("c")
                val counts = mutableMapOf<String, Long>()
                c.keys().forEach { k -> counts[k] = c.getLong(k) }
                Snapshot(o.getLong("t"), counts)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Добавляет снимок, если он отличается от последнего. Возвращает обновлённую историю. */
    fun add(uuid: String, profileId: String, counts: Map<String, Long>, now: Long): List<Snapshot> {
        val history = load(uuid, profileId).toMutableList()
        val last = history.lastOrNull()
        if (last == null || last.counts != counts) {
            history += Snapshot(now, counts)
            while (history.size > MAX) history.removeAt(0)
            save(uuid, profileId, history)
        }
        prefs.edit().putLong("checked_${uuid}_$profileId", now).apply()
        return history
    }

    fun lastChecked(uuid: String, profileId: String): Long =
        prefs.getLong("checked_${uuid}_$profileId", 0L)

    fun clear(uuid: String, profileId: String) {
        prefs.edit().remove(key(uuid, profileId)).apply()
    }

    private fun save(uuid: String, profileId: String, history: List<Snapshot>) {
        val arr = JSONArray()
        history.forEach { s ->
            val c = JSONObject()
            s.counts.forEach { (k, v) -> c.put(k, v) }
            arr.put(JSONObject().put("t", s.time).put("c", c))
        }
        prefs.edit().putString(key(uuid, profileId), arr.toString()).apply()
    }

    companion object {
        const val MAX = 1000

        /** Снимок, от которого считаем прирост «с момента t»: последний сделанный до t, иначе самый первый. */
        fun baseline(history: List<Snapshot>, since: Long): Snapshot? =
            history.lastOrNull { it.time <= since } ?: history.firstOrNull()
    }
}
