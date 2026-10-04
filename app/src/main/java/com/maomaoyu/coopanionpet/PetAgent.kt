package com.maomaoyu.coopanionpet

import org.json.JSONObject

/**
 * AI 自主操作（多步任务）。
 *
 * 循环：读屏 → 问 AI「下一步做什么」→ 解析一行 JSON → **过安全闸门**后执行 → 播报 → 直到 done 或步数用完。
 *
 * 安全：每一步都走 PetA11yService 的闸门；碰到支付/银行/密码一律立刻中止并明说。
 */
object PetAgent {

    private const val MAX_STEPS = 8

    private const val SYSTEM = """你在替主人操作一台安卓手机，替他把事情做完。

每一步我会给你【当前屏幕内容】和【任务】。你只输出**一行 JSON**，不要任何解释：
{"act":"open","app":"微信"}          打开某个应用
{"act":"click","text":"张三"}         按屏幕上的文字点一下
{"act":"tap","x":540,"y":1200}       按坐标点一下
{"act":"type","text":"你好"}          往当前输入框写字
{"act":"swipe","dir":"down"}         上滑/下滑（dir 用 up 或 down）
{"act":"back"} / {"act":"home"}      返回 / 回桌面
{"act":"wait"}                       等一秒再看
{"act":"done","say":"已经发出去了"}    任务完成，跟主人说一句

铁律（违反会被系统拦下）：
- 绝不进行任何支付、转账、红包、充值、提现、理财、银行卡相关操作；
  屏幕上出现这类字眼时，立刻输出 {"act":"done","say":"这个我不能碰"}
- 绝不输入密码、验证码
- 一步只做一件事；做完看新屏幕再决定下一步
- 不确定就 {"act":"done","say":"我不太确定下一步该点哪，你能说得更具体点吗"}"""

    /** 给 AI 的一步请求（不带情绪人设，纯干活）。ai = (system, user) -> 回答 */
    private fun askAI(ai: (String, String) -> String?, task: String, screen: String, done: List<String>): String? {
        val sb = StringBuilder()
        sb.append("任务：").append(task).append("\n\n")
        if (done.isNotEmpty()) sb.append("已经做过的：\n").append(done.joinToString("\n")).append("\n\n")
        sb.append("当前屏幕内容：\n").append(screen.take(1800)).append("\n\n")
        sb.append("输出下一步的 JSON（只一行）：")
        return ai(SYSTEM, sb.toString())
    }

    /**
     * 跑一个多步任务。
     * @param onSay 每步播报（让她说话）
     * @return 结束语（她会说出口）
     */
    fun run(task: String, ai: (String, String) -> String?, onSay: (String) -> Unit): String {
        val s = PetA11yService.instance ?: return "要操作手机得先把无障碍打开哦"
        val done = ArrayList<String>()
        var lastSay = ""

        repeat(MAX_STEPS) { step ->
            val screen = try { s.dump() } catch (e: Exception) { "(读不到屏幕)" }
            // 屏幕上就是支付/银行 → 立刻停，别再问 AI 了
            if (s.lastRefusal != null) {
                return s.lastRefusal!!
            }
            val raw = askAI(ai, task, screen, done)
                ?: return "网络好像不太顺，先停在这儿了" + if (lastSay.isNotEmpty()) "（" + lastSay + "）" else ""
            val act = try {
                val t = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                val st = t.indexOf('{'); val en = t.lastIndexOf('}')
                if (st < 0 || en <= st) return "我没想明白下一步（" + raw.take(40) + "）"
                JSONObject(t.substring(st, en + 1))
            } catch (e: Exception) {
                return "我没想明白下一步"
            }

            val kind = act.optString("act")
            when (kind) {
                "done" -> {
                    val say = act.optString("say").ifBlank { "好啦" }
                    return say
                }
                "open" -> {
                    val app = act.optString("app")
                    val ok = s.openApp(app)
                    lastSay = if (ok) "已打开「" + app + "」" else (s.lastRefusal ?: "没找到「" + app + "」")
                    if (!ok && s.lastRefusal != null) return lastSay
                }
                "click" -> {
                    val txt = act.optString("text")
                    val ok = s.clickText(txt)
                    lastSay = if (ok) "已点「" + txt + "」" else (s.lastRefusal ?: "屏幕上没找到「" + txt + "」")
                    if (!ok && s.lastRefusal != null) return lastSay
                }
                "tap" -> {
                    val ok = s.tap(act.optInt("x"), act.optInt("y"))
                    lastSay = if (ok) "点了一下" else (s.lastRefusal ?: "点不动")
                    if (!ok && s.lastRefusal != null) return lastSay
                }
                "type" -> {
                    val txt = act.optString("text")
                    val ok = s.typeText(txt)
                    lastSay = if (ok) "已输入" else (s.lastRefusal ?: "没有能打字的输入框")
                    if (!ok && s.lastRefusal != null) return lastSay
                }
                "swipe" -> {
                    val down = act.optString("dir").contains("down")
                    val dm = s.resources.displayMetrics
                    val cx = dm.widthPixels / 2
                    val ok = if (down) s.swipe(cx, dm.heightPixels / 3, cx, dm.heightPixels * 2 / 3, 260)
                             else s.swipe(cx, dm.heightPixels * 2 / 3, cx, dm.heightPixels / 3, 260)
                    lastSay = if (ok) (if (down) "已下滑" else "已上滑") else (s.lastRefusal ?: "滑不动")
                }
                "back" -> { s.global(1); lastSay = "已返回" }
                "home" -> { s.global(2); lastSay = "已回桌面" }
                "wait" -> { Thread.sleep(900); lastSay = "等一下…" }
                else -> lastSay = "看不懂这一步"
            }
            done.add("第" + (step + 1) + "步：" + kind + " " + lastSay)
            onSay(lastSay)
            Thread.sleep(700)   // 给界面一点反应时间
        }
        return "走了 " + MAX_STEPS + " 步还没做完，先停一下（最后：" + lastSay + "）"
    }
}
