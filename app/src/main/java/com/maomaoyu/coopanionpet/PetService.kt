package com.maomaoyu.coopanionpet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * 桌宠本体。
 *
 * 窗口**只有桌宠那么大**（不是整屏宽的条带），完全透明、置顶，
 * 可以拖到屏幕任何位置（拖动逻辑在 PetContainer 里）。
 * 里面是加载上游桌宠网页的透明 WebView。
 */
class PetService : Service() {

    private var container_: PetContainer? = null
    private var web_: WebView? = null
    private var server: AssetServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        val s = AssetServer(this)
        s.start()
        server = s
        lastPort = s.port
        attachPet(s.port)
    }

    private fun attachPet(port: Int) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics
        val petW = (dm.density * 150).toInt()
        val petH = (dm.density * 260).toInt()

        val params = WindowManager.LayoutParams(
            petW,
            petH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels - petW) / 2
            y = dm.heightPixels - petH - (dm.density * 40).toInt()
        }

        val web = WebView(this).apply {
            setBackgroundColor(0x00000000)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            webViewClient = WebViewClient()
        }
        web.loadUrl("http://127.0.0.1:$port/web/pet.html?host=window")

        val box = PetContainer(this, wm, params)
        box.setBackgroundColor(0x00000000)
        box.addView(web, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT))

        try {
            wm.addView(box, params)
            container_ = box
            web_ = web
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText("桌宠在跑（可以拖到任意位置）")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        try {
            container_?.let { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) }
        } catch (_: Exception) {
        }
        web_?.destroy()
        web_ = null
        container_ = null
        server?.stop()
        server = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 1101
        private const val CHANNEL_ID = "coopanion_pet"
        @Volatile
        var lastPort: Int = 0
    }
}
