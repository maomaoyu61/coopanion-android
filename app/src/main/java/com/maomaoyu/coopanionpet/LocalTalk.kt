package com.maomaoyu.coopanionpet

/**
 * 离线台词库：没填 API Key（或断网）时用，避免她只能发呆。
 * 简单的关键词匹配 + 每类多条随机，够她撑住日常闲聊。
 */
object LocalTalk {

    private val greet = listOf(
        "主人回来啦～ 我一直在这儿哦", "嗨呀，等你好久了呢", "你终于理我啦，嘿嘿",
        "在的在的，我一直都在", "早上好呀～ 今天也要开心哦", "晚上好，累不累呀"
    )
    private val praise = listOf(
        "嘿嘿…被夸了，有点不好意思", "真的吗？再说一遍嘛～", "唔…尾巴都要翘起来了",
        "谢谢主人～ 你也很棒哦"
    )
    private val whoami = listOf(
        "我是住在这个手机里的大肥鱼呀", "我是你的桌宠，叫大肥鱼～",
        "我是那只哪儿都不去、就赖在你屏幕上的鲸鱼娘"
    )
    private val pat = listOf(
        "嘿嘿…主人的手好暖", "再摸摸嘛，好舒服～", "唔…摸头会让人变笨的啦",
        "呼…这样被摸着，好安心"
    )
    private val bored = listOf(
        "在忙吗？要不要摸摸我", "有点无聊…陪我说说话嘛", "我一直看着你哦，别偷懒～",
        "要不要一起发会儿呆？"
    )
    private val sad = listOf(
        "辛苦啦，先喝口水吧", "抱抱你～ 累了就歇会儿", "没关系，我陪着你呢",
        "别太勉强自己哦，我会心疼的"
    )
    private val food = listOf(
        "想吃鱼…不对，我是鱼！", "说到吃的我就精神了", "主人记得按时吃饭哦"
    )
    private val bye = listOf(
        "晚安～ 梦里有我哦", "去休息吧，我在这儿守着", "明天见呀，我会想你的"
    )
    private val fallback = listOf(
        "唔…我还在学说话，等我变聪明点再聊这个好不好", "这个我还答不上来，先陪我待会儿嘛",
        "（蹭了蹭你）我在听哦，继续说～", "嗯嗯，然后呢？", "嘿嘿…" 
    )

    private fun pick(list: List<String>) = list[(Math.random() * list.size).toInt().coerceIn(0, list.size - 1)]

    /** 根据主人说的话挑一句合适的回应。 */
    fun reply(text: String): String {
        val t = text.trim()
        return when {
            t.isEmpty() -> pick(fallback)
            listOf("你好", "您好", "嗨", "hi", "hello", "在吗", "在不在", "早", "早上好", "晚上好", "晚安", "睡").any { t.contains(it, true) } ->
                if (t.contains("晚安") || t.contains("睡")) pick(bye) else pick(greet)
            listOf("可爱", "好看", "喜欢你", "厉害", "棒", "乖", "聪明").any { t.contains(it, true) } -> pick(praise)
            listOf("你是谁", "名字", "叫什么", "什么鱼", "介绍").any { t.contains(it, true) } -> pick(whoami)
            listOf("摸", "揉", "抱", "亲", "蹭").any { t.contains(it, true) } -> pick(pat)
            listOf("无聊", "干嘛", "做什么", "在忙", "玩").any { t.contains(it, true) } -> pick(bored)
            listOf("累", "难过", "烦", "不开心", "伤心", "压力", "考试", "加班").any { t.contains(it, true) } -> pick(sad)
            listOf("吃", "饿", "饭", "外卖", "喝").any { t.contains(it, true) } -> pick(food)
            t.length <= 2 -> pick(fallback)
            else -> pick(fallback)
        }
    }

    fun greeting(): String = pick(greet)
    fun patReply(): String = pick(pat)
    fun idleLine(): String = pick(bored)
}
