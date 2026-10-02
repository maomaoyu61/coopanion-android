package com.maomaoyu.coopanionpet

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (resources.displayMetrics.density * 20).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        col.addView(TextView(this).apply {
            textSize = 15f
            text = "Coopanion 桌宠（安卓外壳）\n\n" +
                    "做法：透明的置顶悬浮窗 + WebView，加载的就是 Coopanion 上游那份" +
                    "桌宠网页资源（形象 / 八套配色 / 装扮都来自上游）。\n\n" +
                    "步骤：\n1) 点「授予悬浮窗权限」并同意\n2) 点「启动桌宠」\n\n" +
                    "窗口只占屏幕底边一条，其它区域照常操作。"
        })

        col.addView(button("授予悬浮窗权限") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        })

        col.addView(button("启动桌宠") {
            startForegroundService(Intent(this, PetService::class.java))
        })

        col.addView(button("停止桌宠") {
            stopService(Intent(this, PetService::class.java))
        })

        col.addView(button("打开装扮页") {
            val port = if (PetService.lastPort > 0) PetService.lastPort else 8899
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("http://127.0.0.1:$port/web/dress.html")))
        })

        col.addView(button("打开通知设置（给通知权限）") {
            val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            i.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            startActivity(i)
        })

        val scroll = ScrollView(this).apply { addView(col) }
        setContentView(scroll, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }
}
