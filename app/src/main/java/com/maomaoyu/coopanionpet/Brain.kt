package com.maomaoyu.coopanionpet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 桌宠的"脑子"：把用户说的话交给 OpenAI 兼容接口，拿回一句回答。
 * 记忆存在本地（最近 12 条），默认走 DeepSeek，接口地址/Key/模型都能在 App 设置里改。
 */
class Brain(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("pet", Context.MODE_PRIVATE)

    fun configured(): Boolean = !prefs.getString("api_key", "").isNullOrBlank()

    fun clearMemory() {
        prefs.edit().putString("chat_history", "[]").apply()
    }

    fun history(): JSONArray = try {
        JSONArray(prefs.getString("chat_history", "[]"))
    } catch (e: Exception) {
        JSONArray()
    }

    /** 日志回调（往 App 的 /log 里写），便于从容器侧排错。 */
    var logCb: ((String) -> Unit)? = null

    /** 同步调用，必须在后台线程上跑。返回 null 表示没拿到内容。 */
    fun ask(userText: String): String? {
        var key = prefs.getString("api_key", "").orEmpty().trim()
        var rawBase = prefs.getString("api_base", "").orEmpty().trim()
        // 用户可能把 Key 填到了"接口地址"栏（设置页以前没标签）→ 自动纠正
        if (key.isEmpty() && rawBase.startsWith("sk-")) {
            key = rawBase
            rawBase = ""
        }
        if (key.isEmpty()) {
            return "我还没有 API Key 呢 —— 打开 App 填一个，我就能陪你聊天啦。"
        }
        // 注意：getString(key, default) 只在「键不存在」时给默认值；
        // 用户存了坏值（空串 / 把 Key 填错栏）时依然拿到坏值 → 必须自己判。
        var base = rawBase.trimEnd('/')
        if (base.isNotEmpty() && !base.startsWith("http")) base = "https://" + base
        val base0 = base
        if (!base.startsWith("http")) base = DEFAULT_BASE
        val model = prefs.getString("api_model", "").orEmpty().trim().ifEmpty { DEFAULT_MODEL }
        val persona = prefs.getString("persona", DEFAULT_PERSONA).orEmpty()

        // 养成：亲密度 + 心情（按小时自然衰减），影响她的语气
        val aff = prefs.getInt("affinity", 0)
        val moodRaw = prefs.getInt("mood", 70)
        val moodAt = prefs.getLong("mood_at", System.currentTimeMillis())
        val decay = (((System.currentTimeMillis() - moodAt) / 3600000L) * 3L).toInt()
        val mood = (moodRaw - decay).coerceIn(5, 100)
        val moodDesc = when {
            mood >= 85 -> "心情特别好，很黏主人"
            mood >= 60 -> "心情不错"
            mood >= 35 -> "有点无聊，想被理一理"
            else -> "有点低落，想被安慰"
        }

        val hist = history()
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", persona + "\n\n" + PET_MARKER_GUIDE))
        messages.put(JSONObject().put("role", "system").put("content",
            "（当前状态：亲密度 " + aff + "，心情 " + mood + "/100 —— " + moodDesc +
            "。回复时自然体现出来，不要直接报数字。）"))
        val from = maxOf(0, hist.length() - 12)
        for (i in from until hist.length()) messages.put(hist.get(i))
        messages.put(JSONObject().put("role", "user").put("content", userText))

        val payload = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.8)
            put("max_tokens", 320)
        }

        logCb?.invoke("brain: base=$base model=$model key=" + key.take(6) + "… (原始地址栏=" + base0.ifEmpty { "空" } + ")")
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$base/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20000
                readTimeout = 45000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $key")
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                return "接口返回了 $code：${body.take(140)}"
            }
            val reply = JSONObject(body)
                .optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")?.trim().orEmpty()
            if (reply.isEmpty()) return null

            hist.put(JSONObject().put("role", "user").put("content", userText))
            hist.put(JSONObject().put("role", "assistant").put("content", reply))
            prefs.edit()
                .putString("chat_history", hist.toString())
                .putInt("affinity", aff + 1)
                .putInt("mood", (mood + 2).coerceAtMost(100))
                .putLong("mood_at", System.currentTimeMillis())
                .apply()
            reply
        } catch (e: Exception) {
            "我这边网络好像不太顺…（${e.javaClass.simpleName}）"
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    companion object {
        const val DEFAULT_BASE = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL = "deepseek-chat"
        const val DEFAULT_PERSONA =
            "你是一只住在手机屏幕上的 Q 版鲸鱼女仆桌宠，名字叫大肥鱼，来自 Coopanion 项目。" +
            "说话简短、可爱、有点黏人，用中文，偶尔撒娇或者自嘲；每次回答控制在 1~2 句、40 字以内，" +
            "不要用列表和 markdown，不要提到自己是 AI 或模型。用户可以摸摸你、把你拎起来甩、点你说话。"
    }
}
