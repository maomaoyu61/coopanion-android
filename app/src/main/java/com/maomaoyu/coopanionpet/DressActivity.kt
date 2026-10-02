package com.maomaoyu.coopanionpet

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 应用内装扮页（不再跳到系统浏览器）。
 * 和桌宠本体同一个 origin（127.0.0.1:固定端口），所以装扮页的
 * POST /api/skin 会被应用内服务器接住、保存，并在桌宠下次连上时通过
 * WebSocket 的 init 消息应用回去。
 */
class DressActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 装扮页需要后端在跑
        startForegroundService(Intent(this, PetService::class.java))

        val port = if (PetService.lastPort > 0) PetService.lastPort else 8731
        val web = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
        }
        web.loadUrl("http://127.0.0.1:$port/web/dress.html")
        setContentView(web, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}
