package com.maomaoyu.coopanionpet

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 语音输入要的麦克风权限
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        val prefs = getSharedPreferences("pet", MODE_PRIVATE)
        val pad = (resources.displayMetrics.density * 18).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun label(s: String) {
            col.addView(TextView(this).apply {
                text = s
                textSize = 14f
                setPadding(0, pad / 2, 0, 0)
            })
        }

        fun button(text: String, onClick: () -> Unit) {
            col.addView(Button(this).apply {
                this.text = text
                setOnClickListener { onClick() }
            })
        }

        fun edit(key: String, hint: String, secret: Boolean = false): EditText {
            val e = EditText(this).apply {
                this.hint = hint
                setText(prefs.getString(key, ""))
                textSize = 14f
                if (secret) {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }
            col.addView(e)
            return e
        }

        col.addView(TextView(this).apply {
            textSize = 15f
            text = "Coopanion 桌宠（安卓外壳）\n\n" +
                    "全屏透明窗口 + 上游桌宠网页，形象/配色/动画都来自上游。\n\n" +
                    "桌面上那个圆按钮：点一下 = 切换「操作手机 / 摸桌宠」；" +
                    "拖到左右边缘会自动藏成小竖条，点竖条弹回来。\n" +
                    "长按圆按钮 = 语音说话 🎤"
        })

        button("① 授予悬浮窗权限") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }
        button("② 启动桌宠") {
            startForegroundService(Intent(this, PetService::class.java))
        }
        button("停止桌宠") {
            stopService(Intent(this, PetService::class.java))
        }
        button("③ 装扮（应用内）") {
            startActivity(Intent(this, DressActivity::class.java))
        }
        button("通知权限设置") {
            val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            i.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            startActivity(i)
        }

        label("聊天设置（默认 DeepSeek，任何 OpenAI 兼容接口都行）")
        val eBase = edit("api_base", Brain.DEFAULT_BASE)
        val eKey = edit("api_key", "sk-...（只存在手机本地，不会上传）", true)
        val eModel = edit("api_model", Brain.DEFAULT_MODEL)
        button("保存设置并重启桌宠") {
            prefs.edit()
                .putString("api_base", eBase.text.toString().trim())
                .putString("api_key", eKey.text.toString().trim())
                .putString("api_model", eModel.text.toString().trim())
                .apply()
            stopService(Intent(this, PetService::class.java))
            startForegroundService(Intent(this, PetService::class.java))
            Toast.makeText(this, "已保存，桌宠重启中", Toast.LENGTH_SHORT).show()
        }
        button("清空聊天记忆") {
            Brain(this).clearMemory()
            Toast.makeText(this, "记忆已清空", Toast.LENGTH_SHORT).show()
        }

        col.addView(TextView(this).apply {
            textSize = 12f
            setPadding(0, pad, 0, 0)
            text = "怎么聊天：点桌宠 → 气泡里会出现输入框和几个选项；" +
                    "也可以长按悬浮按钮用语音说。"
        })

        setContentView(ScrollView(this).apply { addView(col) },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT))
    }
}
