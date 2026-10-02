package com.maomaoyu.coopanionpet

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * 原生输入条：贴在屏幕**顶部**的一条输入框。
 *
 * 为什么不用网页里的输入框：安卓悬浮窗默认不可聚焦，输入法永远拿不到焦点（试了几版都不稳）。
 * 换成原生 EditText 之后，输入法正常弹；而且**输入法自带的麦克风就能语音输入**，
 * 不用自己写识别。打开期间会把桌宠压到屏幕上半部分，键盘不会挡住它。
 */
class ChatInputActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)

        val pad = (resources.displayMetrics.density * 12).toInt()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFFF2F3F5.toInt())
        }

        val input = EditText(this).apply {
            hint = "跟大肥鱼说点什么…"
            textSize = 15f
            setTextColor(0xFF111111.toInt())
            setHintTextColor(0xFF888888.toInt())
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEND
        }

        val submit = {
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) PetService.instance?.submitText(t)
            input.setText("")
            finish()
        }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submit()
                true
            } else false
        }

        row.addView(input, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "发送"
            setOnClickListener { submit() }
        })

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(row, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
        setContentView(root)

        PetService.instance?.setComposing(true)

        input.requestFocus()
        input.postDelayed({
            try {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            } catch (_: Exception) {
            }
        }, 250)
    }

    override fun onDestroy() {
        PetService.instance?.setComposing(false)
        super.onDestroy()
    }
}
