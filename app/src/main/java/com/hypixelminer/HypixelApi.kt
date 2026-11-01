package com.hypixelminer

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ApiException(message: String) : Exception(message)

/** Коллекция SkyBlock, которая набирается добычей блоков. */
data class MiningItem(val key: String, val title: String, val color: Int)

object MiningItems {
    val ALL = listOf(
        MiningItem("COBBLESTONE", "Булыжник", 0xFF8A8F99.toInt()),
        MiningItem("COAL", "Уголь", 0xFF4A4F59.toInt()),
        MiningItem("IRON_INGOT", "Железо", 0xFFD8C2A8.toInt()),
        MiningItem("GOLD_INGOT", "Золото", 0xFFF2C94C.toInt()),
        MiningItem("DIAMOND", "Алмазы", 0xFF5CE1E6.toInt()),
        MiningItem("INK_SACK:4", "Лазурит", 0xFF3D6BE0.toInt()),
        MiningItem("EMERALD", "Изумруды", 0xFF2ECC71.toInt()),
        MiningItem("REDSTONE", "Редстоун", 0xFFE5383B.toInt()),
        MiningItem("QUARTZ", "Кварц", 0xFFEDE6DA.toInt()),
        MiningItem("OBSIDIAN", "Обсидиан", 0xFF6A3FA0.toInt()),
        MiningItem("GLOWSTONE_DUST", "Светокамень", 0xFFF7D774.toInt()),
        MiningItem("GRAVEL", "Гравий", 0xFF8C8580.toInt()),
        MiningItem("ICE", "Лёд", 0xFF9CC9F5.toInt()),
        MiningItem("NETHERRACK", "Незерак", 0xFFA04848.toInt()),
        MiningItem("SAND", "Песок", 0xFFE3D7A3.toInt()),
        MiningItem("SAND:1", "Красный песок", 0xFFC8743A.toInt()),
        MiningItem("ENDER_STONE", "Эндерняк", 0xFFE6E2A1.toInt()),
        MiningItem("MYCEL", "Мицелий", 0xFF9E7F9F.toInt()),
        MiningItem("SULPHUR", "Сера", 0xFFD9D35B.toInt()),
        MiningItem("MITHRIL_ORE", "Мифрил", 0xFF6FD3C9.toInt()),
        MiningItem("HARD_STONE", "Твёрдый камень", 0xFF6E7480.toInt()),
        MiningItem("GEMSTONE_COLLECTION", "Самоцветы", 0xFFE05AA6.toInt()),
        MiningItem("GLACITE", "Глацит", 0xFFB5E3F2.toInt()),
        MiningItem("UMBER", "Умбра", 0xFF9B6A3B.toInt()),
        MiningItem("TUNGSTEN", "Вольфрам", 0xFF8A95A0.toInt()),
    )
    val KEYS = ALL.map { it.key }.toSet()
}

data class Powder(val title: String, val current: Long, val total: Long)

data class SkyProfile(
    val id: String,
    val name: String,
    val selected: Boolean,
    /** null, если игрок выключил доступ к коллекциям в настройках API */
    val collections: Map<String, Long>?,
    val powders: List<Powder>,
)

object HypixelApi {

    private fun get(url: String, apiKey: String? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", "HypixelMiner/1.0 (Android)")
            c.setRequestProperty("Accept", "application/json")
            if (apiKey != null) c.setRequestProperty("API-Key", apiKey)
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to body
        } catch (e: java.io.IOException) {
            throw ApiException("Нет соединения с сервером. Проверь интернет.")
        } finally {
            c.disconnect()
        }
    }

    /** Ник → (UUID без дефисов, ник в правильном регистре) */
    fun lookupUuid(nick: String): Pair<String, String> {
        val (code, body) = get(
            "https://api.mojang.com/users/profiles/minecraft/" +
                URLEncoder.encode(nick.trim(), "UTF-8")
        )
        if (code == 204 || code == 404 || body.isBlank()) {
            throw ApiException("Игрок «$nick» не найден. Проверь ник.")
        }
        if (code != 200) throw ApiException("Сервис Mojang ответил ошибкой $code. Попробуй позже.")
        val o = JSONObject(body)
        return o.getString("id") to o.optString("name", nick)
    }

    fun loadProfiles(uuid: String, apiKey: String): List<SkyProfile> {
        val (code, body) = get("https://api.hypixel.net/v2/skyblock/profiles?uuid=$uuid", apiKey)
        when (code) {
            200 -> Unit
            403 -> throw ApiException(
                "Hypixel не принял API-ключ. Получи новый на developer.hypixel.net и вставь его в настройках."
            )
            429 -> throw ApiException("Слишком много запросов. Подожди пару минут и обнови снова.")
            else -> throw ApiException("Hypixel ответил ошибкой $code. Попробуй позже.")
        }
        val root = JSONObject(body)
        if (!root.optBoolean("success", false)) {
            throw ApiException(root.optString("cause", "Hypixel вернул ошибку."))
        }
        val arr = root.optJSONArray("profiles")
        if (arr == null || arr.length() == 0) {
            throw ApiException("У этого игрока нет профилей SkyBlock.")
        }

        val result = mutableListOf<SkyProfile>()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val member = p.optJSONObject("members")?.optJSONObject(uuid)

            val collObj = member?.optJSONObject("collection")
            val collections = collObj?.let { obj ->
                val map = mutableMapOf<String, Long>()
                obj.keys().forEach { k -> if (k in MiningItems.KEYS) map[k] = obj.optLong(k, 0L) }
                map
            }

            val powders = mutableListOf<Powder>()
            member?.optJSONObject("mining_core")?.let { mc ->
                listOf(
                    "mithril" to "Мифриловый порошок",
                    "gemstone" to "Самоцветный порошок",
                    "glacite" to "Глацитовый порошок",
                ).forEach { (k, title) ->
                    val current = mc.optLong("powder_$k", -1L)
                    if (current >= 0) {
                        val total = mc.optLong(
                            "powder_${k}_total",
                            current + mc.optLong("powder_spent_$k", 0L)
                        )
                        powders += Powder(title, current, maxOf(total, current))
                    }
                }
            }

            result += SkyProfile(
                id = p.optString("profile_id"),
                name = p.optString("cute_name", "Профиль ${i + 1}"),
                selected = p.optBoolean("selected", false),
                collections = collections,
                powders = powders,
            )
        }
        return result
    }
}
