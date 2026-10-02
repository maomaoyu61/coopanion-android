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

    /** 同步调用，必须在后台线程上跑。返回 null 表示没拿到内容。 */
    fun ask(userText: String): String? {
        val key = prefs.getString("api_key", "").orEmpty().trim()
        if (key.isEmpty()) {
            return "我还没有 API Key 呢 —— 打开 App 填一个，我就能陪你聊天啦。"
        }
        val base = prefs.getString("api_base", DEFAULT_BASE).orEmpty().trim().trimEnd('/')
        val model = prefs.getString("api_model", DEFAULT_MODEL).orEmpty().trim()
        val persona = prefs.getString("persona", DEFAULT_PERSONA).orEmpty()

        val hist = history()
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", persona))
        val from = maxOf(0, hist.length() - 12)
        for (i in from until hist.length()) messages.put(hist.get(i))
        messages.put(JSONObject().put("role", "user").put("content", userText))

        val payload = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.8)
            put("max_tokens", 320)
        }

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
            prefs.edit().putString("chat_history", hist.toString()).apply()
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
