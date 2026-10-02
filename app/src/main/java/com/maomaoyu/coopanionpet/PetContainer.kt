package com.maomaoyu.coopanionpet

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 包住 WebView 的容器：负责"拖着桌宠全屏乱跑"。
 *
 * 关键点：整个屏幕只有一个**桌宠大小的窗口**（不是整屏宽的条带），
 * 手指按下后如果移动超过 touchSlop 就判定为拖动 —— 此时拦截事件、
 * 移动窗口本身（和移植版 deepfish-desktop-pet-android 一个思路）；
 * 只是轻点则完全不拦截，交给 WebView，桌宠的点击互动照常。
 */
@SuppressLint("ViewConstructor")
class PetContainer(
    context: Context,
    private val wm: WindowManager,
    private val params: WindowManager.LayoutParams,
    private val onDragChanged: ((Boolean) -> Unit)? = null
) : FrameLayout(context) {

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                startX = params.x
                startY = params.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging &&
                    (abs(ev.rawX - downRawX) > slop || abs(ev.rawY - downRawY) > slop)) {
                    dragging = true
                    onDragChanged?.invoke(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    params.x = startX + (ev.rawX - downRawX).toInt()
                    params.y = startY + (ev.rawY - downRawY).toInt()
                    try {
                        wm.updateViewLayout(this, params)
                    } catch (_: Exception) {
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = dragging
                if (was) onDragChanged?.invoke(false)
                dragging = false
                if (was) {
                    try {
                        wm.updateViewLayout(this, params)
                    } catch (_: Exception) {
                    }
                }
                return was
            }
        }
        return true
    }
}
