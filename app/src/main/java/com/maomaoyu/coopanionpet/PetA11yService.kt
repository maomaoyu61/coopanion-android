package com.maomaoyu.coopanionpet

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍服务（初版）：让她能在**没有 root** 的情况下操作手机。
 *
 * 能力：读屏幕（控件树）/ 按文字点击 / 坐标点按 / 滑动 / 全局按键(返回·桌面·最近) /
 *      往输入框写字 / 按名字打开应用。
 *
 * 隐私：只在本机、只在用户明确下达指令时动作；不采集、不外传。
 */
class PetA11yService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: PetA11yService? = null
            private set

        fun alive(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 初版不需要被动监听事件；能力都走主动调用
    }

    override fun onInterrupt() {}

    /* ------------ 安全闸门（所有动作都过这里） ------------ */

    /** 当前前台应用是否在禁用名单里。 */
    private fun blocked(): Boolean {
        val pkg = rootInActiveWindow?.packageName?.toString()
        val label = try {
            val ai = packageManager.getApplicationInfo(pkg ?: "", 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) { null }
        return PetSafety.deniedApp(pkg, label)
    }

    /** 当前界面文字里有没有支付敏感词。 */
    private fun screenSensitive(): Boolean = PetSafety.sensitiveScreen(dumpRaw())

    /** 最近一次被拒的原因（给上层回话用）。 */
    @Volatile var lastRefusal: String? = null

    /** 动作闸门：当前应用或界面不安全 → 拦下（返回 true）。 */
    private fun guardScreen(): Boolean {
        if (blocked()) {
            lastRefusal = PetSafety.reason(rootInActiveWindow?.packageName?.toString(), null)
            return true
        }
        if (screenSensitive()) {
            lastRefusal = "这个界面涉及支付/账户安全，我不动手"
            return true
        }
        return false
    }

    /** 点按闸门：要点的那个东西本身也不能敏感。 */
    private fun guardTap(text: String): Boolean {
        if (guardScreen()) return true
        if (PetSafety.sensitiveTap(text)) {
            lastRefusal = "「" + text + "」涉及支付/账户安全，我不点"
            return true
        }
        return false
    }

    /** 每次动作前清一下上次的拒绝原因。 */
    private fun clearRefusal() { lastRefusal = null }

    /* ------------ 读屏幕 ------------ */

    /** 屏幕上能看到的文字 + 大概位置（简版，给人和 AI 看）。 */
    fun dump(): String {
        if (blocked()) {
            lastRefusal = PetSafety.reason(rootInActiveWindow?.packageName?.toString(), null)
            return lastRefusal!!
        }
        return dumpRaw()
    }

    /** 不做安全检查的原始读屏（内部用）。 */
    private fun dumpRaw(): String {
        val root = rootInActiveWindow ?: return "(读不到当前界面)"
        val sb = StringBuilder()
        val seen = HashSet<String>()
        walk(root, sb, seen, 0)
        return if (sb.isEmpty()) "(界面上没有可读文字)" else sb.toString()
    }

    private fun walk(n: AccessibilityNodeInfo?, sb: StringBuilder, seen: MutableSet<String>, depth: Int) {
        if (n == null || depth > 40) return
        val t = n.text?.toString()?.trim().orEmpty()
        val d = n.contentDescription?.toString()?.trim().orEmpty()
        val label = if (t.isNotEmpty()) t else d
        if (label.isNotEmpty() && !seen.contains(label)) {
            seen.add(label)
            val r = Rect()
            n.getBoundsInScreen(r)
            sb.append(label.take(40))
                .append(" [").append((r.left + r.right) / 2).append(",").append((r.top + r.bottom) / 2).append("]")
                .append(if (n.isClickable) "*" else "")
                .append('\n')
        }
        for (i in 0 until n.childCount) walk(n.getChild(i), sb, seen, depth + 1)
    }

    /* ------------ 动作 ------------ */

    /** 按文字（或描述）点一下。 */
    fun clickText(text: String): Boolean {
        lastRefusal = null
        if (guardTap(text)) return false
        val root = rootInActiveWindow ?: return false
        val node = find(root, text, 0) ?: return false
        var cur: AccessibilityNodeInfo? = node
        var up = 0
        while (cur != null && up < 6) {
            if (cur.isClickable) {
                if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                val r = Rect(); cur.getBoundsInScreen(r)
                return tap((r.left + r.right) / 2, (r.top + r.bottom) / 2)
            }
            cur = cur.parent; up++
        }
        val r = Rect(); node.getBoundsInScreen(r)
        return tap((r.left + r.right) / 2, (r.top + r.bottom) / 2)
    }

    private fun find(n: AccessibilityNodeInfo?, text: String, depth: Int): AccessibilityNodeInfo? {
        if (n == null || depth > 40) return null
        val t = n.text?.toString().orEmpty()
        val d = n.contentDescription?.toString().orEmpty()
        if (t.contains(text) || d.contains(text)) return n
        for (i in 0 until n.childCount) {
            val r = find(n.getChild(i), text, depth + 1)
            if (r != null) return r
        }
        return null
    }

    /** 坐标点按。 */
    fun tap(x: Int, y: Int): Boolean {
        if (guardScreen()) return false
        val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build()
        return dispatchGesture(g, null, null)
    }

    /** 滑动（像素坐标）。 */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long = 260L): Boolean {
        if (guardScreen()) return false
        val p = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build()
        return dispatchGesture(g, null, null)
    }

    /** 全局按键：1=返回 2=桌面 3=最近任务 4=通知栏。 */
    fun global(which: Int): Boolean {
        val a = when (which) {
            1 -> GLOBAL_ACTION_BACK
            2 -> GLOBAL_ACTION_HOME
            3 -> GLOBAL_ACTION_RECENTS
            else -> GLOBAL_ACTION_NOTIFICATIONS
        }
        return performGlobalAction(a)
    }

    /** 往当前聚焦的输入框写字。 */
    fun typeText(text: String): Boolean {
        if (guardScreen()) return false
        val root = rootInActiveWindow ?: return false
        val n = findFocusedEditable(root, 0) ?: return false
        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findFocusedEditable(n: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (n == null || depth > 40) return null
        if (n.isFocused && n.isEditable && !n.isPassword) return n
        for (i in 0 until n.childCount) {
            val r = findFocusedEditable(n.getChild(i), depth + 1)
            if (r != null) return r
        }
        return null
    }

    /** 按应用名（中文/英文/包名）打开。 */
    /** 常见应用的中文名 → 包名（包可见性受限时的兜底）。 */
    private val ALIAS = mapOf(
        "微信" to "com.tencent.mm", "wechat" to "com.tencent.mm",
        "qq" to "com.tencent.mobileqq", "腾讯qq" to "com.tencent.mobileqq",
        "设置" to "com.android.settings", "相机" to "com.android.camera",
        "相册" to "com.miui.gallery", "图库" to "com.miui.gallery",
        "浏览器" to "com.android.browser", "时钟" to "com.android.deskclock",
        "日历" to "com.android.calendar", "计算器" to "com.android.calculator2",
        "音乐" to "com.miui.player", "视频" to "com.miui.video",
        "地图" to "com.baidu.BaiduMap", "淘宝" to "com.taobao.taobao",
        "哔哩哔哩" to "tv.danmaku.bili", "b站" to "tv.danmaku.bili",
        "抖音" to "com.ss.android.ugc.aweme", "知乎" to "com.zhihu.android",
        "微博" to "com.sina.weibo", "番茄小说" to "com.dragon.read",
        "记事本" to "com.android.notes", "文件管理" to "com.android.documentsui"
    )

    /** 按应用名（中文/英文/包名）打开。 */
    fun openApp(nameOrPkg: String): Boolean {
        val want0 = nameOrPkg.trim()
        if (PetSafety.deniedApp(want0, want0)) {
            lastRefusal = PetSafety.reason(want0, want0)
            return false
        }
        val want = want0
        if (want.isEmpty()) return false

        fun launch(pkg: String): Boolean {
            val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            return try { startActivity(i); true } catch (e: Exception) { false }
        }

        // ① 先当包名试
        if (launch(want)) return true
        // ② 别名表
        ALIAS[want.lowercase()]?.let { if (launch(it)) return true }
        ALIAS[want]?.let { if (launch(it)) return true }
        // ③ 查所有可启动应用，按名字匹配
        try {
            val pm = packageManager
            val main = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(main, 0)
            PetService.instance?.server?.log("openApp: 查到 " + list.size + " 个可启动应用")
            var fuzzy: String? = null
            for (ri in list) {
                val label = ri.loadLabel(pm).toString()
                val pkg = ri.activityInfo.packageName
                if (label.equals(want, true)) return launch(pkg)
                if (fuzzy == null && (label.contains(want) || pkg.equals(want, true))) fuzzy = pkg
            }
            if (fuzzy != null) return launch(fuzzy)
        } catch (e: Exception) {
            PetService.instance?.server?.log("openApp 出错: " + e.javaClass.simpleName)
        }
        return false
    }
}
