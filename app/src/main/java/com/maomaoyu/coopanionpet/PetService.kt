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
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * 桌宠本体 —— 全屏透明窗口，不设任何"框"。
 *
 * 之前几版我在小窗口里量尺寸、撑窗口、自己搬窗口，全是跟框较劲：
 * 上游（桌面版）本来就是"一个全屏窗口，桌宠自己在里面走、被拖、弹菜单"。
 * 所以这一版把限制全部去掉：
 *
 *  - 窗口 = 整个可用区域（去掉状态栏和导航栏，保证下拉通知/手势还能用）
 *  - 全透明，不显示任何背景（页面里再注入强制透明样式）
 *  - roam:"free" → 桌宠自己在整屏范围内走动，腿部动画由上游播放
 *  - 拖动/菜单/气泡都由上游在页面内处理，绝不被裁
 *
 * 代价：全屏窗口会吃掉触摸。所以给了开关：
 * 通知栏的「触摸穿透」可以在"能撸桌宠"和"正常用手机"之间切换。
 */
class PetService : Service() {

    private var web_: WebView? = null
    private var server: AssetServer? = null
    private var wm_: WindowManager? = null
    private var params_: WindowManager.LayoutParams? = null
    private var passthrough = true
    private var btn_: TextView? = null
    private var btnParams_: WindowManager.LayoutParams? = null
    private var btnMoved = false
    private val handler = Handler(Looper.getMainLooper())
    private val brain by lazy { Brain(this) }
    private var voice: android.speech.SpeechRecognizer? = null
    private val longPress = Runnable { startVoice() }
    private var btnCollapsed = false
    private var btnSide = 1   // 0=左 1=右
    private var btnDownX = 0f
    private var btnDownY = 0f
    private var btnStartX = 0
    private var btnStartY = 0

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
        s.events = object : AssetServer.PetEvents {
            override fun onPetText(text: String) {
                handleUserText(text)
            }

            override fun onPetTouch() {
            }

            override fun onPetHello() {
                handler.postDelayed({
                    val srv = server ?: return@postDelayed
                    if (brain.configured()) {
                        srv.sendSay("我在这儿～ 想聊点什么？", listOf("hop"))
                        srv.sendAsk("想聊什么呀？", listOf("随便聊聊", "夸夸我", "讲个冷笑话"), true)
                    } else {
                        srv.sendSay("看到我啦～ 先去 App 里填个 API Key，我就能陪你聊天了。", listOf("nod"))
                    }
                }, 1600)
            }

            override fun onPetOther(type: String, raw: String) {
            }
        }
        attachPet(s.port)
    }

    private fun dimen(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun attachPet(port: Int) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm_ = wm
        val dm = resources.displayMetrics
        val statusBar = dimen("status_bar_height")
        val navBar = dimen("navigation_bar_height")

        val params = WindowManager.LayoutParams(
            dm.widthPixels,
            (dm.heightPixels - statusBar - navBar).coerceAtLeast(320),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = statusBar
        }
        params_ = params

        val web = WebView(this).apply {
            setBackgroundColor(0x00000000)
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

        val box = FrameLayout(this).apply {
            setBackgroundColor(0x00000000)
            addView(web, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT))
        }
        try {
            wm.addView(box, params)
            root_ = box
        } catch (e: Exception) {
            stopSelf()
            return
        }
        addToggleButton(wm)
    }

    /** 悬浮小按钮：点一下切换「操作手机 / 摸桌宠」；拖到屏幕左右边缘会自动藏成一条透明小竖条。 */
    private fun addToggleButton(wm: WindowManager) {
        val prefs = getSharedPreferences("pet", Context.MODE_PRIVATE)
        val dm = resources.displayMetrics
        val density = dm.density
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val size = (density * 46).toInt()
        btnSide = prefs.getInt("btn_side", 1)
        val p = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("btn_x", screenW - size - (density * 10).toInt())
            y = prefs.getInt("btn_y", (screenH * 0.6f).toInt())
        }
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val tv = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable()
            text = if (passthrough) "\uD83D\uDD90" else "\uD83D\uDC3E"
        }
        paintButton(tv, passthrough)
        tv.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    btnDownX = ev.rawX; btnDownY = ev.rawY
                    btnStartX = p.x; btnStartY = p.y; btnMoved = false
                    v.postDelayed(longPress, 650)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(ev.rawX - btnDownX) > slop || abs(ev.rawY - btnDownY) > slop) {
                        btnMoved = true
                        v.removeCallbacks(longPress)
                    }
                    if (btnMoved) {
                        if (btnCollapsed) {
                            p.y = (btnStartY + (ev.rawY - btnDownY)).toInt()
                                .coerceIn(0, screenH - p.height)
                        } else {
                            p.x = (btnStartX + (ev.rawX - btnDownX)).toInt()
                                .coerceIn(0, screenW - p.width)
                            p.y = (btnStartY + (ev.rawY - btnDownY)).toInt()
                                .coerceIn(0, screenH - p.height)
                        }
                        try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    if (!btnMoved) {
                        if (btnCollapsed) expandButton() else togglePassthrough()
                    } else {
                        // 拖到左右边缘附近 → 自动收起成小竖条
                        val cx = p.x + p.width / 2f
                        if (cx < screenW * 0.06f || cx > screenW * 0.94f) {
                            btnSide = if (cx < screenW / 2f) 0 else 1
                            collapseButton()
                        }
                        saveButtonPos(prefs)
                    }
                    true
                }
                else -> false
            }
        }
        try { wm.addView(tv, p) } catch (_: Exception) {}
        btn_ = tv
        btnParams_ = p
        if (prefs.getBoolean("btn_collapsed", false)) collapseButton()
    }

    /** 用户说的话（打字或语音）→ 交给 Brain → 让桌宠说出来。 */
    private fun handleUserText(text: String) {
        if (text.isBlank()) return
        val srv = server ?: return
        srv.sendThinking(true)
        Thread({
            val reply = brain.ask(text)
            handler.post({
                server?.sendThinking(false)
                if (!reply.isNullOrBlank()) server?.sendSay(reply, listOf("nod"))
            })
        }, "brain").start()
    }

    /** 长按悬浮按钮 = 语音输入（走安卓系统识别）。 */
    private fun startVoice() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            server?.sendSay("先去 App 里给我麦克风权限吧～", listOf("nod"))
            return
        }
        try {
            voice?.destroy()
            val sr = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
            voice = sr
            sr.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    server?.sendSay("我在听…", listOf("look"))
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: android.os.Bundle?) {}
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}

                override fun onError(error: Int) {
                    sr.destroy(); voice = null
                    server?.sendSay("没听清～再长按我一下？", listOf("nod"))
                }

                override fun onResults(results: android.os.Bundle?) {
                    val said = results
                        ?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    sr.destroy(); voice = null
                    if (!said.isNullOrBlank()) handleUserText(said)
                }
            })
            val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            }
            sr.startListening(i)
        } catch (e: Exception) {
            server?.sendSay("语音没起来…（${e.javaClass.simpleName}）", listOf("nod"))
        }
    }

    private fun paintButton(v: TextView, gray: Boolean) {
        val color = if (gray) 0xCC666666.toInt() else 0xCC1FA463.toInt()
        val bg = GradientDrawable().apply {
            shape = if (btnCollapsed) GradientDrawable.RECTANGLE else GradientDrawable.OVAL
            cornerRadius = if (btnCollapsed) (resources.displayMetrics.density * 4) else 0f
            setColor(color)
        }
        v.background = bg
        v.text = if (btnCollapsed) "" else if (gray) "\uD83D\uDD90" else "\uD83D\uDC3E"
        v.alpha = if (btnCollapsed) 0.35f else 1f
        v.setTextColor(0xFFFFFFFF.toInt())
    }

    private fun saveButtonPos(prefs: android.content.SharedPreferences) {
        val p = btnParams_ ?: return
        prefs.edit()
            .putInt("btn_x", p.x).putInt("btn_y", p.y)
            .putInt("btn_side", btnSide)
            .putBoolean("btn_collapsed", btnCollapsed).apply()
    }

    /** 收起成贴着屏幕边缘的透明小竖条 */
    private fun collapseButton() {
        val wm = wm_ ?: return
        val v = btn_ ?: return
        val p = btnParams_ ?: return
        val dm = resources.displayMetrics
        btnCollapsed = true
        val barW = (dm.density * 7).toInt()
        val barH = (dm.density * 64).toInt()
        p.width = barW
        p.height = barH
        p.x = if (btnSide == 0) 0 else dm.widthPixels - barW
        p.y = p.y.coerceIn(0, dm.heightPixels - barH)
        paintButton(v, passthrough)
        try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
        saveButtonPos(getSharedPreferences("pet", Context.MODE_PRIVATE))
    }

    /** 从小竖条弹回完整按钮 */
    private fun expandButton() {
        val wm = wm_ ?: return
        val v = btn_ ?: return
        val p = btnParams_ ?: return
        val dm = resources.displayMetrics
        btnCollapsed = false
        val size = (dm.density * 46).toInt()
        p.width = size
        p.height = size
        p.x = if (btnSide == 0) (dm.density * 6).toInt() else dm.widthPixels - size - (dm.density * 6).toInt()
        p.y = p.y.coerceIn(0, dm.heightPixels - size)
        paintButton(v, passthrough)
        try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
        saveButtonPos(getSharedPreferences("pet", Context.MODE_PRIVATE))
    }

    /** 把按钮提到最上层（主窗口变可点后可能压住它）。 */
    private fun bringButtonToFront() {
        val wm = wm_ ?: return
        val v = btn_ ?: return
        val p = btnParams_ ?: return
        try { wm.removeView(v) } catch (_: Exception) {}
        try { wm.addView(v, p) } catch (_: Exception) {}
    }

    private var root_: FrameLayout? = null

    /** 触摸穿透开关：开着的时候手机正常用，关掉才能撸桌宠。 */
    fun togglePassthrough(): Boolean {
        val p = params_ ?: return passthrough
        passthrough = !passthrough
        p.flags = if (passthrough) {
            p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        try {
            wm_?.updateViewLayout(root_, p)
        } catch (_: Exception) {
        }
        btn_?.let { b -> paintButton(b, passthrough) }
        bringButtonToFront()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
        return passthrough
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
        val toggle = PendingIntent.getBroadcast(
            this, 1, Intent(this, PassthroughReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(if (passthrough) "触摸穿透中（点通知按钮可恢复撸桌宠）"
                            else "桌宠在整屏活动；挡手就点下面切触摸穿透")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(open)
            .addAction(buildAction(toggle))
            .setOngoing(true)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun buildAction(pi: PendingIntent): Notification.Action =
        Notification.Action.Builder(
            android.R.drawable.ic_menu_view,
            if (passthrough) "恢复操作桌宠" else "触摸穿透",
            pi).build()

    override fun onDestroy() {
        try {
            root_?.let { wm_?.removeView(it) }
        } catch (_: Exception) {
        }
        try { voice?.destroy() } catch (_: Exception) {}
        voice = null
        try { btn_?.let { wm_?.removeView(it) } } catch (_: Exception) {}
        btn_ = null
        web_?.destroy()
        web_ = null
        root_ = null
        server?.stop()
        server = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 1101
        private const val CHANNEL_ID = "coopanion_pet"

        @Volatile
        var lastPort: Int = 0

        @Volatile
        var instance: PetService? = null
    }

    init {
        instance = this
    }
}
