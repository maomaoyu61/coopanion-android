package com.maomaoyu.coopanionpet

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 应用内装扮页。
 *
 * 之前的「无法连接」是因为服务还没起来就 loadUrl 了（127.0.0.1 是本地的，
 * 不需要联网）。这里先起服务，再**等端口真正可连**，然后才加载页面。
 */
class DressActivity : Activity() {

    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, PetService::class.java))

        val view = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
        }
        setContentView(view, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web = view

        Thread {
            val port = waitForPort()
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    web?.loadUrl("http://127.0.0.1:$port/web/dress.html")
                }
            }
        }, "dress-wait").start()
    }

    /** 等服务端口可连（最多约 6 秒），连上了才加载页面。 */
    private fun waitForPort(): Int {
        for (i in 0 until 60) {
            val port = if (PetService.lastPort > 0) PetService.lastPort else 8731
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
                return port
            } catch (_: Exception) {
                Thread.sleep(100)
            }
        }
        return if (PetService.lastPort > 0) PetService.lastPort else 8731
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        super.onDestroy()
    }
}
