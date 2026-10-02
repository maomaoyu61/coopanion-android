package com.maomaoyu.coopanionpet

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.webkit.WebView

/**
 * 能弹出输入法的 WebView。
 *
 * 悬浮窗默认是 FLAG_NOT_FOCUSABLE（不抢焦点，这样不会影响你正常用手机），
 * 代价是**输入法永远拿不到焦点** —— 所以之前点输入框不弹键盘。
 *
 * 这里做个小把戏：页面里一旦有输入框获得焦点，系统会来要 InputConnection，
 * 我们就趁机把窗口临时变成可聚焦（弹出键盘）；输入会话关闭后再变回去。
 */
class PetWebView(
    context: Context,
    private val onNeedInput: (Boolean) -> Unit
) : WebView(context) {

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        onNeedInput(true)
        return object : InputConnectionWrapper(base, true) {
            override fun closeConnection() {
                try {
                    super.closeConnection()
                } catch (_: Exception) {
                }
                onNeedInput(false)
            }
        }
    }
}
