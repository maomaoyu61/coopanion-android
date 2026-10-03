package com.maomaoyu.coopanionpet

import org.json.JSONArray
import org.json.JSONObject

/**
 * 气泡脚本解析：1:1 移植自上游
 * `packages/cortico-world-desktop-pet/src/script.ts`（含 VOCAB 词表）。
 *
 * 标记规则（与上游一致）：
 * - `【开心, 跳】` 阻塞式：先做这些动作，然后用后面的文字**另起一个气泡**继续说
 * - `<眨眼>`       内联式：打字打到这个位置时顺手做一下，**不换气泡**
 * - 词可写英文 id 或中文名；不认识的词会被丢弃
 * - 内联标记超过 32 字或跨行时按普通文字处理
 */
object PetScript {

    /** (id, kind, 中文别名) */
    private val VOCAB: List<Triple<String, String, List<String>>> = listOf(
        Triple("neutral", "expression", listOf("平静")),
        Triple("happy", "expression", listOf("开心", "高兴")),
        Triple("wink", "expression", listOf("眨眼")),
        Triple("love", "expression", listOf("喜欢", "爱心")),
        Triple("shy", "expression", listOf("害羞")),
        Triple("surprised", "expression", listOf("惊讶", "吃惊")),
        Triple("angry", "expression", listOf("生气")),
        Triple("sad", "expression", listOf("难过", "伤心")),
        Triple("sleepy", "expression", listOf("犯困", "困")),
        Triple("thinking", "expression", listOf("思考", "想想")),
        Triple("stand", "motion", listOf("站起", "站")),
        Triple("jump", "motion", listOf("跳", "跳起来")),
        Triple("hop", "motion", listOf("小跳", "蹦")),
        Triple("look", "motion", listOf("张望", "看看")),
        Triple("turn", "motion", listOf("转身")),
        Triple("nod", "motion", listOf("点头")),
        Triple("shake", "motion", listOf("摇头")),
        Triple("spin", "motion", listOf("转圈")),
        Triple("sit", "motion", listOf("坐下", "坐")),
        Triple("sleep", "motion", listOf("睡觉", "睡")),
        Triple("dizzy", "motion", listOf("晕", "转晕")),
        Triple("walk", "motion", listOf("走走", "散步")),
        Triple("run", "motion", listOf("跑", "跑起来"))
    )

    private val BY_WORD = HashMap<String, String>()
    init {
        for ((id, _, zh) in VOCAB) {
            BY_WORD[id] = id
            for (z in zh) BY_WORD[z] = id
        }
    }

    private const val INLINE_TAG_MAX = 32

    private fun vocabId(word: String): String? {
        val t = word.trim()
        return BY_WORD[t.lowercase()] ?: BY_WORD[t]
    }

    private fun words(inner: String, dropped: MutableList<String>): List<String> {
        val out = ArrayList<String>()
        for (w in inner.split(Regex("[,，、\\s]+"))) {
            if (w.isEmpty()) continue
            val id = vocabId(w)
            if (id != null) out.add(id) else dropped.add(w)
        }
        return out
    }

    /**
     * 把一段脚本解析成网页 `say` 需要的 beats：
     * `[{ text, actions: [...], anchors: [{at, actions}] }]`
     */
    fun beatsJson(script: String): JSONArray {
        val dropped = ArrayList<String>()
        val beats = JSONArray()
        var actions = JSONArray()
        val text = StringBuilder()
        var anchors = JSONArray()

        fun push() {
            if (text.isBlank() && actions.length() == 0 && anchors.length() == 0) return
            beats.put(JSONObject().apply {
                put("text", text.toString())
                put("actions", actions)
                put("anchors", anchors)
            })
            actions = JSONArray()
            text.setLength(0)
            anchors = JSONArray()
        }

        var i = 0
        while (i < script.length) {
            val ch = script[i]
            if (ch == '【') {
                val end = script.indexOf('】', i + 1)
                if (end < 0) { text.append(script.substring(i)); break }
                val acts = words(script.substring(i + 1, end), dropped)
                push()
                actions = JSONArray(acts)
                i = end + 1
                continue
            }
            if (ch == '<' || ch == '＜') {
                val close = if (ch == '<') '>' else '＞'
                val end = script.indexOf(close, i + 1)
                val inner = if (end < 0) "" else script.substring(i + 1, end)
                if (end < 0 || inner.length > INLINE_TAG_MAX || inner.contains('\n')) {
                    text.append(ch); i++; continue
                }
                val acts = words(inner, dropped)
                if (acts.isNotEmpty()) anchors.put(JSONObject().apply {
                    put("at", text.length)
                    put("actions", JSONArray(acts))
                })
                i = end + 1
                continue
            }
            text.append(ch)
            i++
        }
        push()
        if (beats.length() == 0) {
            beats.put(JSONObject().apply {
                put("text", script)
                put("actions", JSONArray())
                put("anchors", JSONArray())
            })
        }
        return beats
    }

    /** 给 AI 看的词表（等价上游 vocabTable()）。 */
    fun vocabTable(): String {
        val ex = VOCAB.filter { it.second == "expression" }
            .joinToString(" ") { it.first + "(" + it.third.first() + ")" }
        val mo = VOCAB.filter { it.second == "motion" }
            .joinToString(" ") { it.first + "(" + it.third.first() + ")" }
        return "表情：$ex\n动作：$mo"
    }
}

/**
 * 追加到 system 提示词：告诉 AI 可以使用动作标记。
 * 对应上游 ENV_PROMPT.md 里的约定，词表来自 PetScript.VOCAB。
 */
val PET_MARKER_GUIDE: String = buildString {
    append("说话时可以夹带表情和动作标记，它们会真的做出来：\n")
    append("- 【开心】放在句首：先做这个动作，然后用后面的文字另起一个气泡。例：【开心】今天也辛苦啦！\n")
    append("- <眨眼> 夹在句中：打字打到那里时顺手做一下，不换气泡。例：要不要<眨眼>歇一会儿？\n")
    append("- 一条回话最多用一两个，别堆；不用也完全可以。\n")
    append("可用词（写英文或中文都行，不认识的会被忽略）：\n")
    append(PetScript.vocabTable())
}
