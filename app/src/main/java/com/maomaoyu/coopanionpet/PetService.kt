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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

/**
 * 桌宠本体。
 *
 * - 窗口**就是桌宠大小**（三档可调），透明置顶，可在整个屏幕上自己溜达、也能手拖。
 * - 走动 = 移动窗口本身（移植版同思路），同时给桌宠发左右交替的 walk 消息让它播走路动画。
 * - 网页内部漫游关掉（roam:"off"），否则它会在小窗口里撞墙。
 * - **轻点桌宠时窗口向上临时撑开**（底边不动，所以桌宠位置不变），
 *   这样长按菜单/气泡才有地方显示、不会被窗口裁掉。
 */
class PetService : Service() {

    private var box_: PetContainer? = null
    private var web_: WebView? = null
    private var server: AssetServer? = null
    private var wm_: WindowManager? = null
    private var params_: WindowManager.LayoutParams? = null

    private val handler = Handler(Looper.getMainLooper())
    private var curX = 0f
    private var curY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var moving = false
    private var tick = 0
    private var userDragging = false
    private var expanded = false
    private var petW = 0
    private var petH = 0
    private var screenW = 0
    private var screenH = 0
    private var density = 1f

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
        handler.postDelayed(roamTick, 2500)
    }

    private fun sizeOf(index: Int): Pair<Int, Int> = when (index) {
        0 -> (100 * density).toInt() to (170 * density).toInt()
        2 -> (150 * density).toInt() to (260 * density).toInt()
        else -> (120 * density).toInt() to (205 * density).toInt()
    }

    private fun attachPet(port: Int) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm_ = wm
        val dm = resources.displayMetrics
        density = dm.density
        screenW = dm.widthPixels
        screenH = dm.heightPixels

        val idx = getSharedPreferences("pet", Context.MODE_PRIVATE).getInt("size", 1)
        val (w, h) = sizeOf(idx)
        petW = w
        petH = h

        val params = WindowManager.LayoutParams(
            petW, petH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW - petW) / 2
            y = screenH - petH - (dm.density * 48).toInt()
        }
        params_ = params
        curX = params.x.toFloat()
        curY = params.y.toFloat()

        val web = WebView(this).apply {
            setBackgroundColor(0x00000000)
            alpha = 0f
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            if (Build.VERSION.SDK_INT >= 29) {
                @Suppress("DEPRECATION")
                settings.forceDark = WebSettings.FORCE_DARK_OFF
            }
            if (Build.VERSION.SDK_INT >= 33) {
                settings.setAlgorithmicDarkeningAllowed(false)
            }
            webViewClient = WebViewClient()
        }
        web.loadUrl("http://127.0.0.1:$port/web/pet.html?host=window")
        web_ = web

        val container = PetContainer(this, wm, params, { dragging ->
            userDragging = dragging
            if (!dragging) {
                collapseMenuRoom()
                val p = params_
                if (p != null) {
                    curX = p.x.toFloat()
                    curY = p.y.toFloat()
                }
                handler.removeCallbacks(roamTick)
                handler.postDelayed(roamTick, 2500)
            }
        }, {
            expandForMenu()   // 轻点：撑开上方空间，菜单才不会被裁
        })
        container.setBackgroundColor(0x00000000)
        container.addView(web, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT))
        try {
            wm.addView(container, params)
            box_ = container
        } catch (e: Exception) {
            stopSelf()
            return
        }

        val fadeIn = web
        handler.postDelayed({
            try {
                fadeIn.animate().alpha(1f).setDuration(240).start()
            } catch (_: Exception) {
            }
        }, 900)
    }

    /* ---------- 轻点：临时向上撑开，给菜单/气泡留位置 ---------- */

    private fun expandForMenu() {
        val p = params_ ?: return
        val extra = (density * 300).toInt()
        if (!expanded) {
            expanded = true
            p.height = petH + extra
            p.y = curY.toInt() - extra          // 底边不动 → 桌宠位置不变
        }
        try {
            wm_?.updateViewLayout(box_, p)
        } catch (_: Exception) {
        }
        handler.removeCallbacks(collapseRunnable)
        handler.postDelayed(collapseRunnable, 9000)
    }

    private val collapseRunnable = Runnable { collapseMenuRoom() }

    private fun collapseMenuRoom() {
        val p = params_ ?: return
        if (!expanded) return
        expanded = false
        p.height = petH
        p.y = curY.toInt()
        try {
            wm_?.updateViewLayout(box_, p)
        } catch (_: Exception) {
        }
    }

    /* ---------- 全屏漫游 ---------- */

    private val roamTick = object : Runnable {
        override fun run() {
            if (userDragging || expanded) {
                handler.postDelayed(this, 500)
                return
            }
            if (!moving) {
                targetX = Random.nextFloat() * (screenW - petW).coerceAtLeast(1)
                targetY = Random.nextFloat() * (screenH - petH).coerceAtLeast(1)
                moving = true
                tick = 0
                server?.petWalk(Random.nextBoolean())
            }
            val dx = targetX - curX
            val dy = targetY - curY
            val dist = hypot(dx, dy)
            if (dist < 6f) {
                moving = false
                handler.postDelayed(this, 1800L + Random.nextLong(4200))
                return
            }
            val step = min(dist, screenW / 90f)
            curX += dx / dist * step
            curY += dy / dist * step
            val p = params_
            if (p != null) {
                p.x = curX.toInt()
                p.y = curY.toInt()
                try {
                    wm_?.updateViewLayout(box_, p)
                } catch (_: Exception) {
                }
            }
            // 约每 0.55 秒补一条 walk（左右交替），走路动画就不断
            if (tick++ % 16 == 0) server?.petWalk(false)
            handler.postDelayed(this, 33)
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
            .setContentText("桌宠在跑（可拖动、会溜达；轻点它可展开菜单）")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            box_?.let { wm_?.removeView(it) }
        } catch (_: Exception) {
        }
        web_?.destroy()
        web_ = null
        box_ = null
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
