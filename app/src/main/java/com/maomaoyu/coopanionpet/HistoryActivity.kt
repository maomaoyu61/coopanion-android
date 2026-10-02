package com.maomaoyu.coopanionpet

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 聊天记录：主人说的话在右（蓝色），她说的话在左（白色）。 */
class HistoryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val prefs = getSharedPreferences("pet", MODE_PRIVATE)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((d * 14).toInt(), (d * 14).toInt(), (d * 14).toInt(), (d * 14).toInt())
            setBackgroundColor(0xFFEEF1FB.toInt())
        }

        val arr = try {
            org.json.JSONArray(prefs.getString("chat_history", "[]"))
        } catch (e: Exception) {
            org.json.JSONArray()
        }

        col.addView(TextView(this).apply {
            text = if (arr.length() == 0) "还没有聊天记录～" else "聊天记录（最近 " + arr.length() + " 条）"
            textSize = 13f
            setTextColor(0xFF6B74A8.toInt())
            setPadding(0, 0, 0, (d * 8).toInt())
        })

        val maxW = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val isUser = o.optString("role") == "user"
            col.addView(TextView(this).apply {
                text = o.optString("content")
                textSize = 14f
                setTextColor(if (isUser) 0xFFFFFFFF.toInt() else 0xFF18203A.toInt())
                setPadding((d * 14).toInt(), (d * 10).toInt(), (d * 14).toInt(), (d * 10).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = d * 14
                    setColor(if (isUser) 0xFF4759AD.toInt() else 0xFFFFFFFF.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(maxW, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = (d * 8).toInt()
                    gravity = if (isUser) Gravity.END else Gravity.START
                }
            })
        }

        setContentView(ScrollView(this).apply { addView(col) },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT))
    }
}
