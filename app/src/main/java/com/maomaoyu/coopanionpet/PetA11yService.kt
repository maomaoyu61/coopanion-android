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

    /* ------------ 读屏幕 ------------ */

    /** 屏幕上能看到的文字 + 大概位置（简版，给人和 AI 看）。 */
    fun dump(): String {
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
        val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build()
        return dispatchGesture(g, null, null)
    }

    /** 滑动（像素坐标）。 */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long = 260L): Boolean {
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
    fun openApp(nameOrPkg: String): Boolean {
        val want = nameOrPkg.trim()
        if (want.isEmpty()) return false
        // 先当包名试
        val direct = packageManager.getLaunchIntentForPackage(want)
        if (direct != null) {
            direct.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            return try { startActivity(direct); true } catch (e: Exception) { false }
        }
        // 再按应用名匹配
        val pm = packageManager
        val main = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(main, 0)
        for (ri in list) {
            val label = ri.loadLabel(pm).toString()
            val pkg = ri.activityInfo.packageName
            if (label.equals(want, true) || label.contains(want) || pkg.equals(want, true)) {
                val i = pm.getLaunchIntentForPackage(pkg) ?: continue
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                return try { startActivity(i); true } catch (e: Exception) { false }
            }
        }
        return false
    }
}
