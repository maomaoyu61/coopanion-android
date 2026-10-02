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
 * - 窗口就是桌宠大小（固定最小档 100x170dp），透明置顶，可全屏拖动、也会自己溜达。
 * - **腿的动画交给上游自己**：app 里给桌宠发 `roam:"free"`，它就会在窗口内自己走动/小跑
 *   （之前我关掉 roam 想防它撞墙，结果把动画也关了 —— 撞墙没关系，窗口本身在移动，
 *   看上去就是它在屏幕上走）。
 * - 走动 = 移动窗口本身；另外每 400ms 用 JS 量一次菜单/气泡的真实尺寸，
 *   **按需把窗口撑到刚好装下**（底边和左边不动 → 桌宠屏幕位置不变），
 *   菜单/二级菜单就不会再被裁掉。
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
    private var userDragging = false
    private var petW = 0
    private var petH = 0
    private var screenW = 0
    private var screenH = 0
    private var density = 1f
    private var menuUp = 0
    private var menuRight = 0

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
        handler.postDelayed(uiWatch, 1500)
    }

    private fun attachPet(port: Int) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm_ = wm
        val dm = resources.displayMetrics
        density = dm.density
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        petW = (density * 100).toInt()
        petH = (density * 170).toInt()

        val params = WindowManager.LayoutParams(
            petW, petH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW - petW) / 2
            y = screenH - petH - (density * 48).toInt()
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
                val p = params_
                if (p != null) {
                    curX = p.x.toFloat()
                    curY = (p.y + menuUp).toFloat()
                }
                handler.removeCallbacks(roamTick)
                handler.postDelayed(roamTick, 2000)
            }
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

    /* -------- 按需把窗口撑到刚好装下菜单/气泡 -------- */

    private val uiWatch = object : Runnable {
        override fun run() {
            try {
                web_?.evaluateJavascript(JS_MEASURE) { r -> applyUiBounds(r) }
            } catch (_: Exception) {
            }
            handler.postDelayed(this, 400)
        }
    }

    private fun applyUiBounds(result: String?) {
        val p = params_ ?: return
        var up = 0
        var right = 0
        val raw = result?.trim()?.trim('"')
        if (raw != null && raw.startsWith("[") && raw.endsWith("]")) {
            val nums = raw.trim('[', ']').split(",").mapNotNull { it.trim().toFloatOrNull() }
            if (nums.size == 4) {
                up = (nums[1] * density).toInt().coerceAtLeast(0)
                val needRight = (nums[2] * density).toInt() - petW
                right = needRight.coerceAtLeast(0)
            }
        }
        up = up.coerceIn(0, (density * 430).toInt())
        right = right.coerceIn(0, (density * 240).toInt())
        if (up == menuUp && right == menuRight) return
        menuUp = up
        menuRight = right
        p.y = curY.toInt() - menuUp
        p.height = petH + menuUp
        p.width = petW + menuRight
        try {
            wm_?.updateViewLayout(box_, p)
        } catch (_: Exception) {
        }
    }

    /* -------- 全屏漫游（只动窗口；腿的动画由网页自己放） -------- */

    private val roamTick = object : Runnable {
        override fun run() {
            if (userDragging) {
                handler.postDelayed(this, 400)
                return
            }
            if (!moving) {
                targetX = Random.nextFloat() * (screenW - petW - menuRight).coerceAtLeast(1)
                targetY = Random.nextFloat() * (screenH - petH).coerceAtLeast(1)
                moving = true
            }
            val dx = targetX - curX
            val dy = targetY - curY
            val dist = hypot(dx, dy)
            if (dist < 6f) {
                moving = false
                handler.postDelayed(this, 2200L + Random.nextLong(3800))
                return
            }
            val step = min(dist, screenW / 130f)
            curX += dx / dist * step
            curY += dy / dist * step
            val p = params_
            if (p != null) {
                p.x = curX.toInt()
                p.y = curY.toInt() - menuUp
                try {
                    wm_?.updateViewLayout(box_, p)
                } catch (_: Exception) {
                }
            }
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
            .setContentText("桌宠在跑（可拖动、会溜达）")
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

        /** 量菜单/气泡/选项面板的真实边界（CSS px，视口坐标）。 */
        private const val JS_MEASURE = """
(function(){
  var sels=['.menu','.bubble','.ask','.b-opts','.b-own','.b-hint'];
  var l=1e9,t=1e9,r=-1e9,b=-1e9,found=false;
  for (var i=0;i<sels.length;i++){
    var els=document.querySelectorAll(sels[i]);
    for (var j=0;j<els.length;j++){
      var e=els[j];
      if (e.hidden || e.offsetParent===null) continue;
      var cs=getComputedStyle(e);
      if (cs.display==='none'||cs.visibility==='hidden'||parseFloat(cs.opacity)<0.05) continue;
      var q=e.getBoundingClientRect();
      if (q.width<2||q.height<2) continue;
      found=true;
      if(q.left<l)l=q.left; if(q.top<t)t=q.top; if(q.right>r)r=q.right; if(q.bottom>b)b=q.bottom;
    }
  }
  return found ? ('['+[Math.floor(l),Math.floor(t),Math.ceil(r),Math.ceil(b)].join(',')+']') : 'null';
})()
"""
    }
}
