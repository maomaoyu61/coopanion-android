package com.maomaoyu.coopanionpet

/**
 * 把主人的话翻译成手机操作（无需 root，走无障碍服务）。
 *
 * 口令词：句首可带「大肥鱼」/「肥鱼」——带了就一定是给她的指令，
 * 不带但有明确指令词（打开/点/打字…）也认。
 *
 * 安全边界：
 *  - 只有「允许她操作手机」打开、且无障碍服务已开启时才生效
 *  - 只认**以指令词开头**的句子，普通聊天不会被误当成指令
 *  - 密码输入框一律不碰；不采集内容、不外传
 */
object PetCmd {

    private val WAKE = listOf("大肥鱼", "肥鱼")

    /** 去掉句首口令词；返回 null 表示这句话没带口令词。 */
    fun stripWake(raw: String): String? {
        val s = raw.trim().replace("\u3000", " ")
        for (w in WAKE) {
            if (s.startsWith(w)) {
                return s.removePrefix(w).trimStart('，', ',', '！', '!', '。', '.', ' ', '、')
            }
        }
        return null
    }

    /** 命中指令 → 返回她要说的话；不是指令 → null（交给 AI 聊天）。 */
    fun handle(raw: String): String? {
        val s = PetA11yService.instance ?: return null
        val stripped = stripWake(raw)
        val woke = stripped != null
        val t = (stripped ?: raw).trim().replace("\u3000", " ")
        if (t.isEmpty()) return if (woke) "在呢～" else null
        if (t.length < 2) return null

        fun act(ok: Boolean, okMsg: String, failMsg: String) = if (ok) okMsg else failMsg

        // 打开应用
        for (p in listOf("帮我打开", "帮我启动", "打开", "启动")) {
            if (t.startsWith(p)) {
                val app = t.removePrefix(p).trim().trim('。', '！', '!', '.', ' ', '「', '」')
                if (app.isEmpty()) return "要打开哪个呀？"
                return act(s.openApp(app), "已打开「" + app + "」", "没找到「" + app + "」这个应用")
            }
        }
        // 全局按键
        if (t == "返回" || t == "回退" || t == "退回去" || t == "按返回" || t == "返回上一页")
            return act(s.global(1), "已返回", "按不动…")
        if (t == "回桌面" || t == "回主屏" || t == "桌面" || t == "回主页")
            return act(s.global(2), "已回桌面", "按不动…")
        if (t == "最近任务" || t == "多任务" || t == "看看后台")
            return act(s.global(3), "已打开最近任务", "按不动…")
        if (t == "通知栏" || t == "看通知" || t == "下拉通知")
            return act(s.global(4), "已拉下通知栏", "按不动…")
        // 看屏幕
        if (t == "屏幕上有什么" || t == "看看屏幕" || t == "读一下屏幕") {
            val d = s.dump()
            val first = d.split("\n").filter { it.isNotBlank() }.take(5).joinToString("；")
            return if (first.isBlank()) "这个界面我读不到文字" else "屏幕上：" + first
        }
        // 点按
        for (p in listOf("点一下", "点击", "点")) {
            if (t.startsWith(p)) {
                val what = t.removePrefix(p).trim().trim('。', '！', '!', '.', ' ', '「', '」', '“', '”')
                if (what.isEmpty()) return "要打哪个呀？"
                return act(s.clickText(what), "已点「" + what + "」", "屏幕上没找到「" + what + "」")
            }
        }
        // 打字
        for (p in listOf("打字：", "打字:", "输入", "帮我输入")) {
            if (t.startsWith(p)) {
                val what = t.removePrefix(p).trim()
                if (what.isEmpty()) return "要打什么呀？"
                return act(s.typeText(what), "已输入", "没有能打字的输入框（密码框我不碰）")
            }
        }
        // 滑动
        if (t == "下滑" || t == "往下滑" || t == "往下翻" || t == "上滑" || t == "往上滑" || t == "往上翻") {
            val down = t.contains("下滑") || t.contains("往下")
            val dm = s.resources.displayMetrics
            val cx = dm.widthPixels / 2
            val ok = if (down) s.swipe(cx, dm.heightPixels / 3, cx, dm.heightPixels * 2 / 3, 260)
                     else s.swipe(cx, dm.heightPixels * 2 / 3, cx, dm.heightPixels / 3, 260)
            return act(ok, if (down) "已下滑" else "已上滑", "滑不动…")
        }
        return if (woke) "嗯？要我做什么（试试：打开微信 / 返回 / 点一下登录）" else null
    }
}
