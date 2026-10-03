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
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
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
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsWarned = false
    private var ttsRetry = 0
    private var greeted = false
    private var inputWanted = false
    private var chat_: TextView? = null
    private var chatParams_: WindowManager.LayoutParams? = null
    private var mic_: TextView? = null
    private var micParams_: WindowManager.LayoutParams? = null
    private val brain by lazy { Brain(this) }
    private var voice: android.speech.SpeechRecognizer? = null
    private val longPress = Runnable { startVoice() }
    private var input_: android.widget.LinearLayout? = null
    private var inputParams_: WindowManager.LayoutParams? = null
    private var inputEdit_: android.widget.EditText? = null
    private var bubble_: TextView? = null
    private var bubbleParams_: WindowManager.LayoutParams? = null
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
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            server?.log("TTS status=" + status)
            if (status != TextToSpeech.SUCCESS && ttsRetry < 3) {
                ttsRetry++
                handler.postDelayed({
                    server?.log("TTS 初始化失败，第 " + ttsRetry + " 次重试")
                    try { tts?.shutdown() } catch (_: Exception) {}
                    tts = TextToSpeech(this) { s2 ->
                        ttsReady = s2 == TextToSpeech.SUCCESS
                        server?.log("TTS 重试 status=" + s2)
                        if (ttsReady) try { tts?.language = Locale.CHINA } catch (_: Exception) {}
                    }
                }, 2000L * ttsRetry)
            }
            server?.log("TTS 初始化 status=" + status + " (0=SUCCESS)")
            if (ttsReady) {
                try {
                    server?.log("TTS 可用引擎: " + tts?.engines?.joinToString(",").orEmpty())
                    val lang = tts?.setLanguage(Locale.CHINA)
                    server?.log("TTS setLanguage=" + lang)
                    if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                        ttsReady = false
                        server?.log("TTS 缺少中文数据")
                    } else {
                        ttsReady = true
                    }
                } catch (e: Exception) {
                    server?.log("TTS 语言设置异常: " + e.message)
                }
            }
        }
        val s = AssetServer(this)
        s.log("=== PetService 启动 v3.18 ===")
        brain.logCb = { line -> s.log(line) }
        s.onEval = { code ->
            handler.post {
                try {
                    web_?.evaluateJavascript(code) { r -> s.log("JS结果: " + (r ?: "null")) }
                } catch (e: Exception) {
                    s.log("JS异常: " + e.message)
                }
            }
        }
        s.start()
        server = s
        lastPort = s.port
        s.events = object : AssetServer.PetEvents {
            override fun onPetText(text: String) {
                handleUserText(text)
            }

            override fun onPetControl(action: String) {
                when (action) {
                    "dress" -> try {
                        startActivity(Intent(this@PetService, DressActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {
                    }
                    "quit" -> stopSelf()
                }
            }

            override fun onPetTouch() {
                onTouched()
            }

            override fun onPetHello() {
                // 每次她上线都把保存的外观设置同步过去（否则启动前拖的滑块不会有反应）
                server?.log("同步外观设置")
                server?.hideHoverButtons()
                server?.sendPrefs(
                    scale = getSharedPreferences("pet", Context.MODE_PRIVATE)
                        .getFloat("scale", 1f).toDouble(),
                    sound = getSharedPreferences("pet", Context.MODE_PRIVATE)
                        .getBoolean("sound", true))
                if (greeted) return
                greeted = true
                handler.postDelayed({
                    val srv = server ?: return@postDelayed
                    if (brain.configured()) {
                        say("我在这儿～ 想聊点什么？")
                    } else {
                        say("看到我啦～ 先去 App 里填个 API Key，我就能陪你聊天了。")
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

        // 贴着屏幕下沿：只避开状态栏，不再扣导航栏（否则桌宠会浮在导航栏上方）
        val params = WindowManager.LayoutParams(
            dm.widthPixels,
            (dm.heightPixels - statusBar).coerceAtLeast(320),
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

        val web = PetWebView(this) { want ->
            setWindowFocusable(want)
        }.apply {
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
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    server?.log("页面: " + m.message().take(180) + " @" + m.lineNumber())
                    return true
                }
            }
        }
        web.addJavascriptInterface(JsBridge(), "AndroidPet")
        web.loadUrl("http://127.0.0.1:$port/web/pet.html?host=window")
        web_ = web

        val box = PetRoot(this).apply {
            setBackgroundColor(0x00000000)
            addView(web, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT))
        }
        if (passthrough) params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try {
            wm.addView(box, params)
            root_ = box
        } catch (e: Exception) {
            stopSelf()
            return
        }
        addNativeBubble(wm, dm)
        addStatusBar(wm, dm)
        try {
            val f = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            registerReceiver(screenRx, f)
        } catch (_: Exception) {
        }
        if (!loopsStarted) {
            loopsStarted = true
            handler.postDelayed(linkWatch, 8000)
            handler.postDelayed(dshPoll, 2500)
            handler.postDelayed(idleChat, 90000)
            handler.postDelayed(reminder, 30000)
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
                    // 长按语音已停用（同上）
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
                        syncMic()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    if (!btnMoved) {
                        server?.log("点了模式按钮 (collapsed=" + btnCollapsed + ")")
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
        addMicButton(wm, p)
        if (prefs.getBoolean("btn_collapsed", false)) collapseButton()
    }

    /**
     * 盯着页面里有没有输入框获得焦点 —— 有就把窗口临时变成可聚焦并主动唤起键盘，
     * 没有就变回"不抢焦点"。之前指望系统来要 InputConnection 是错的：
     * 窗口不可聚焦时，系统压根不会来要，回调永远不会触发。
     */
    private val imeWatch = object : Runnable {
        override fun run() {
            val w = web_
            if (w != null) {
                try {
                    w.evaluateJavascript(
                        "(function(){var e=document.activeElement;" +
                        "return (e&&(e.tagName===INPUT||e.tagName===TEXTAREA||e.isContentEditable))?1:0;})()"
                    ) { r ->
                        val wants = r != null && r.contains("1")
                        if (wants != inputWanted) {
                            inputWanted = wants
                            setWindowFocusable(wants)
                            if (wants) {
                                try {
                                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                                    w.requestFocus()
                                    imm.showSoftInput(w, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            handler.postDelayed(this, 350)
        }
    }

    /** 悬浮输入条：不再用 Activity，所以不会跳转到 App。 */
    private fun showChatInput() {
        val wm = wm_ ?: return
        val dm = resources.displayMetrics
        if (input_ == null) {
            val bar = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setBackgroundColor(0xFFF2F3F5.toInt())
                setPadding((dm.density * 12).toInt(), (dm.density * 8).toInt(),
                    (dm.density * 12).toInt(), (dm.density * 8).toInt())
            }
            val et = android.widget.EditText(this).apply {
                hint = "跟大肥鱼说点什么…"
                textSize = 15f
                setTextColor(0xFF111111.toInt())
                setHintTextColor(0xFF888888.toInt())
                setSingleLine(true)
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            }
            val btn = Button(this).apply { text = "发送" }
            bar.addView(et, android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(btn)
            val p = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
            val send = {
                val txt = et.text.toString().trim()
                if (txt.isNotEmpty()) handleUserText(txt)
                et.setText("")
                hideChatInput()
            }
            btn.setOnClickListener { send() }
            et.setOnEditorActionListener { _, id, _ ->
                if (id == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                    send(); true
                } else false
            }
            if (Build.VERSION.SDK_INT >= 30) {
                bar.setOnApplyWindowInsetsListener { view, insets ->
                    val ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
                    p.y = ime
                    try { wm.updateViewLayout(view, p) } catch (_: Exception) {}
                    insets
                }
            }
            input_ = bar
            inputParams_ = p
            inputEdit_ = et
        }
        val v = input_ ?: return
        val p = inputParams_ ?: return
        setComposing(true)
        try { wm.removeView(v) } catch (_: Exception) {}
        try { wm.addView(v, p) } catch (_: Exception) {}
        raiseButtons()
        val et = inputEdit_
        if (et != null) {
            et.requestFocus()
            handler.postDelayed({
                try {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
                    imm.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                } catch (_: Exception) {
                }
            }, 250)
        }
        say("我在这儿呢，你说～")
    }

    private fun hideChatInput() {
        val wm = wm_ ?: return
        setComposing(false)
        val v = input_ ?: return
        try { wm.removeView(v) } catch (_: Exception) {}
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(v.windowToken, 0)
        } catch (_: Exception) {
        }
    }

    /** 原生气泡：回复直接画在屏幕上（网页那套气泡在安卓上不可靠）。 */
    private fun addNativeBubble(wm: WindowManager, dm: android.util.DisplayMetrics) {
        val v = TextView(this).apply {
            textSize = 14.5f
            setTextColor(0xFF18203A.toInt())
            setLineSpacing(dm.density * 3, 1f)
            setPadding((dm.density * 15).toInt(), (dm.density * 11).toInt(),
                (dm.density * 15).toInt(), (dm.density * 20).toInt())
            background = BubbleBg()
            elevation = dm.density * 6
            visibility = android.view.View.GONE
        }
        val p = WindowManager.LayoutParams(
            (dm.widthPixels * 0.62f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels * 0.11f).toInt()
            y = (dm.heightPixels * 0.52f).toInt()
        }
        try { wm.addView(v, p) } catch (_: Exception) {}
        bubble_ = v
        bubbleParams_ = p
    }

    private val hideBubble = Runnable { bubble_?.visibility = android.view.View.GONE }

    /**
     * 让气泡跟着桌宠走：周期性问页面 #pet 的包围盒，把气泡摆到她头顶上方。
     */
    /** 全屏容器：只旁听触摸事件，用来识别"双击桌宠 → 打开对话框"，不影响她自己的手势。 */
    private inner class PetRoot(ctx: Context) : FrameLayout(ctx) {
        private val gd = android.view.GestureDetector(ctx,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                    server?.log("双击桌宠 → 打开对话框")
                    showChatInput()
                    return true
                }
            })

        override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
            try { gd.onTouchEvent(ev) } catch (_: Exception) {}
            return super.dispatchTouchEvent(ev)
        }
    }

    /** 浅色圆角气泡 + 下方居中小尾巴。 */
    private inner class BubbleBg : android.graphics.drawable.Drawable() {
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = android.graphics.Paint.Style.FILL
        }
        private val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFBFD2F7.toInt()
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 1.6f
        }
        override fun draw(c: android.graphics.Canvas) {
            val dd = resources.displayMetrics.density
            val r = dd * 16
            val tail = dd * 9
            val b = bounds
            val rect = android.graphics.RectF(b.left.toFloat() + dd, b.top.toFloat() + dd,
                b.right.toFloat() - dd, b.bottom.toFloat() - tail)
            val path = android.graphics.Path()
            path.addRoundRect(rect, r, r, android.graphics.Path.Direction.CW)
            val cx = b.exactCenterX()
            path.moveTo(cx - tail, rect.bottom - dd)
            path.lineTo(cx, b.bottom.toFloat() - dd)
            path.lineTo(cx + tail, rect.bottom - dd)
            path.close()
            c.drawPath(path, fill)
            c.drawPath(path, line)
        }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** 网页主动推来的桌宠位置（CSS 像素）→ 让气泡跟着她；只在真的移动时才挪窗口，避免掉帧。 */
    private inner class JsBridge {
        @android.webkit.JavascriptInterface
        fun pos(x: Int, y: Int, w: Int, h: Int) {
            handler.post { followPet(x, y, w, h) }
        }
    }

    private var lastBx = Int.MIN_VALUE
    private var lastBy = Int.MIN_VALUE

    private fun followPet(cx: Int, cy: Int, cw: Int, ch: Int) {
        val wm = wm_ ?: return
        val b = bubble_ ?: return
        val p = bubbleParams_ ?: return
        if (b.visibility != android.view.View.VISIBLE) return
        val dens = resources.displayMetrics.density
        val px = (cx * dens).toInt()
        val py = (cy * dens).toInt()
        val pw = (cw * dens).toInt()
        val sw = resources.displayMetrics.widthPixels
        val bh = if (b.height > 0) b.height else (dens * 64f).toInt()
        val bx = (px + pw / 2 - p.width / 2).coerceIn(0, (sw - p.width).coerceAtLeast(0))
        // 状态条贴她头顶，气泡再叠在状态条上方
        val ph = (ch * dens).toInt()
        val posMode = petPrefs().getString("status_pos", "feet")
        val sv = status_
        val sp = statusParams_
        var bubbleTop = py - (dens * 8f).toInt()          // 气泡默认贴她头顶
        if (sv != null && sp != null && sv.visibility == android.view.View.VISIBLE && posMode != "off") {
            val sh = if (sv.height > 0) sv.height else (dens * 22f).toInt()
            val sy = if (posMode == "head") (py - sh - (dens * 6f).toInt()).coerceAtLeast(0)
                     else (py + ph + (dens * 6f).toInt())   // 脚下（默认）
            sp.x = ((sw - sp.width) / 2).coerceAtLeast(0)
            sp.y = sy
            try { wm.updateViewLayout(sv, sp) } catch (_: Exception) {}
            if (posMode == "head") bubbleTop = sy - (dens * 6f).toInt()
        }
        val by = (bubbleTop - bh).coerceAtLeast(0)
        if (Math.abs(bx - lastBx) < 1 && Math.abs(by - lastBy) < 1) return
        lastBx = bx
        lastBy = by
        p.x = bx
        p.y = by
        try { wm.updateViewLayout(b, p) } catch (_: Exception) {}
    }

    /** 把气泡重新提到最上层（全屏桌宠窗口被 updateViewLayout 时会压住它）。 */
    private fun raiseBubble() {
        val wm = wm_ ?: return
        val v = bubble_ ?: return
        val p = bubbleParams_ ?: return
        if (v.visibility != android.view.View.VISIBLE) return
        try { wm.removeView(v) } catch (_: Exception) {}
        try { wm.addView(v, p) } catch (_: Exception) {}
    }

    private fun showBubble(text: String) {
        val wm = wm_ ?: return
        val v = bubble_ ?: return
        val p = bubbleParams_ ?: return
        server?.log("显示原生气泡: " + text.take(40))
        v.text = text
        v.visibility = android.view.View.VISIBLE
        // 有些 ROM 对 GONE→VISIBLE 的悬浮窗不刷新，直接重加最稳
        try { wm.removeView(v) } catch (_: Exception) {}
        try { wm.addView(v, p) } catch (_: Exception) {}
        raiseButtons()
        handler.removeCallbacks(hideBubble)
        handler.postDelayed(hideBubble, 9000)
    }

    /** 设置桌宠大小（0.6–1.5）。 */
    fun applyScale(s: Double) {
        server?.sendPrefs(scale = s)
    }

    /** 开关音效。 */
    fun applySound(on: Boolean) {
        server?.sendPrefs(sound = on)
    }

    /** 原生输入条提交的一句话。 */
    fun submitText(text: String) {
        handleUserText(text)
    }

    /** 输入中：把桌宠的活动区域压到屏幕上半部分，键盘弹出来也挡不住它。 */
    fun setComposing(on: Boolean) {
        val p = params_ ?: return
        val dm = resources.displayMetrics
        val statusBar = dimen("status_bar_height")
        val navBar = dimen("navigation_bar_height")
        val full = (dm.heightPixels - statusBar).coerceAtLeast(320)
        val want = if (on) (full * 0.45f).toInt() else full
        if (p.height == want) return
        p.height = want
        p.y = statusBar
        try { wm_?.updateViewLayout(root_, p) } catch (_: Exception) {}
        raiseBubble()
        raiseButtons()
    }

    /** 页面要输入时临时让窗口可聚焦（键盘才能弹出来），输入结束再变回不抢焦点。 */
    private fun setWindowFocusable(want: Boolean) {
        val p = params_ ?: return
        p.flags = if (want) {
            p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (want) {
            p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            try { web_?.requestFocus() } catch (_: Exception) {}
        }
        try { wm_?.updateViewLayout(root_, p) } catch (_: Exception) {}
        raiseBubble()
        raiseButtons()
    }

    /**
     * 朗读一句话。
     * 后台 Service 绑不上系统 TTS 引擎（日志里初始化回调从未触发），
     * 所以优先用 Service 的 TTS，失败就交给网页的 speechSynthesis —— 同一个系统引擎，
     * 但由前台页面发起，能正常出声。
     */
    /* ================= P1：DSH 状态联动 / 主动搭话 / 摸头回话 / 番茄钟 ================= */

    private var status_: TextView? = null
    private var statusParams_: WindowManager.LayoutParams? = null
    private var dshOk = true
    private var dshState = ""
    private var dshStatus = ""
    private var lastUserAt = System.currentTimeMillis()
    private var lastIdleChatAt = 0L
    private var touchCount = 0
    private var touchWindowStart = 0L
    private var lastPatReplyAt = 0L
    private var loopsStarted = false
    private var linkWatchOn = false
    private var deadStreak = 0
    private var lastReloadAt = 0L
    private var lastNotifyApp = ""
    private var lastNotifyAt = 0L

    private fun petPrefs() = getSharedPreferences("pet", Context.MODE_PRIVATE)

    private fun addStatusBar(wm: WindowManager, dm: android.util.DisplayMetrics) {
        val v = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding((dm.density * 10).toInt(), (dm.density * 5).toInt(),
                (dm.density * 10).toInt(), (dm.density * 5).toInt())
            background = GradientDrawable().apply {
                cornerRadius = dm.density * 10
                setColor(0xD91B2233.toInt())
            }
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = android.view.View.GONE
        }
        val p = WindowManager.LayoutParams(
            (dm.widthPixels * 0.72f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels * 0.14f).toInt()
            y = dimen("status_bar_height") + (dm.density * 6).toInt()
        }
        try { wm.addView(v, p) } catch (_: Exception) {}
        status_ = v
        statusParams_ = p
    }

    private fun showStatus(t: String) {
        val wm = wm_ ?: return
        val v = status_ ?: return
        val p = statusParams_ ?: return
        v.text = t
        v.visibility = android.view.View.VISIBLE
        try { wm.removeView(v) } catch (_: Exception) {}
        try { wm.addView(v, p) } catch (_: Exception) {}
    }

    private fun hideStatus() {
        status_?.visibility = android.view.View.GONE
    }

    private val dshPoll = object : Runnable {
        override fun run() {
            if (petPrefs().getBoolean("dsh_link", true)) {
                Thread({
                    try {
                        val c = (java.net.URL("http://127.0.0.1:8755/").openConnection()
                            as java.net.HttpURLConnection)
                        c.connectTimeout = 800
                        c.readTimeout = 800
                        val txt = c.inputStream.bufferedReader().readText()
                        c.disconnect()
                        val o = org.json.JSONObject(txt)
                        val st = o.optString("state")
                        val tx = o.optString("text")
                        handler.post { applyDshState(st, tx) }
                    } catch (_: Exception) {
                    }
                }, "dshpoll").start()
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun applyDshState(st: String, text: String) {
        if (st == dshState && text == dshStatus) return
        server?.log("DSH 状态 -> " + st + " | " + text)
        dshState = st
        dshStatus = text
        when (st) {
            "working", "thinking", "waiting" -> {
                server?.sendThinking(true)
                showStatus(text.ifBlank { "正在干活…" })
            }
            "done" -> {
                server?.sendThinking(false)
                hideStatus()
                if (petPrefs().getBoolean("dsh_celebrate", true)) say("干完啦～ 主人辛苦啦！")
            }
            else -> {
                server?.sendThinking(false)
                hideStatus()
            }
        }
    }

    private val idleChat = object : Runnable {
        override fun run() {
            val p = petPrefs()
            if (p.getBoolean("idle_chat", false)) {
                val mins = p.getInt("idle_min", 15).coerceAtLeast(2)
                val gap = (System.currentTimeMillis() - lastUserAt) / 60000
                if (gap >= mins && System.currentTimeMillis() - lastIdleChatAt > 20 * 60000L) {
                    lastIdleChatAt = System.currentTimeMillis()
                    lastUserAt = System.currentTimeMillis()
                    Thread({
                        val line = brain.ask("（现在没人跟你说话，你自己待着。请主动跟主人说一句话，20字以内，符合你的人设）")
                        handler.post { if (!line.isNullOrBlank()) say(line) }
                    }, "idlechat").start()
                }
            }
            handler.postDelayed(this, 60000)
        }
    }

    private fun onTouched() {
        lastUserAt = System.currentTimeMillis()
        val now = System.currentTimeMillis()
        if (now - touchWindowStart > 40000) {
            touchWindowStart = now
            touchCount = 0
        }
        touchCount++
        val p = petPrefs()
        p.edit().putInt("affinity", (p.getInt("affinity", 0) + 1).coerceAtMost(99999)).apply()
        if (touchCount >= 4 && now - lastPatReplyAt > 180000) {
            touchCount = 0
            lastPatReplyAt = now
            Thread({
                val line = brain.ask("（主人刚摸了摸你的头。请用一句话回应，20字以内，语气亲近）")
                handler.post { if (!line.isNullOrBlank()) say(line) }
            }, "pat").start()
        }
    }

    private val reminder = object : Runnable {
        override fun run() {
            val p = petPrefs()
            val at = p.getLong("remind_at", 0L)
            if (at > 0 && System.currentTimeMillis() >= at) {
                val label = p.getString("remind_label", "时间到啦")
                p.edit().putLong("remind_at", 0L).apply()
                say(label + "～ 该歇歇啦！")
            }
            handler.postDelayed(this, 30000)
        }
    }

    /* ================= 连线自愈：桌宠网页断线就重载 ================= */

    /** 每 8 秒看一眼桌宠 socket；连续两次（约 16 秒）断着就重载网页，最多 20 秒重载一次。 */
    private val linkWatch = object : Runnable {
        override fun run() {
            val s = server
            if (s != null) {
                if (s.isPetConnected()) {
                    deadStreak = 0
                } else {
                    deadStreak++
                    if (deadStreak >= 5 && System.currentTimeMillis() - lastReloadAt > 60000) {
                        lastReloadAt = System.currentTimeMillis()
                        deadStreak = 0
                        s.log("桌宠 socket 断线超过 40 秒 → 自动重载网页")
                        reloadPet()
                    }
                }
            }
            handler.postDelayed(this, 8000)
        }
    }

    /* ================= P2：省电 / 试听 / 重载 ================= */

    /** 省电：息屏时暂停网页渲染，亮屏恢复。 */
    private val screenRx = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (!petPrefs().getBoolean("power_save", true)) return
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> try { web_?.onPause() } catch (_: Exception) {}
                Intent.ACTION_SCREEN_ON -> {
                    try { web_?.onResume() } catch (_: Exception) {}
                    // 省电暂停可能把网页的 socket 弄断了 → 亮屏后自检，断了就重载
                    handler.postDelayed({
                        if (server?.isPetConnected() != true) {
                            server?.log("亮屏后发现桌宠 socket 断了 → 自动重载")
                            reloadPet()
                        }
                    }, 2500)
                }
            }
        }
    }

    /** 收到微信/QQ 通知（只听 App 名，不看内容）。 */
    fun onAppMessage(app: String) {
        if (!petPrefs().getBoolean("notify_pet", false)) return
        val now = System.currentTimeMillis()
        if (app == lastNotifyApp && now - lastNotifyAt < 60000) return
        lastNotifyApp = app
        lastNotifyAt = now
        server?.log("收到 " + app + " 通知")
        handler.post { say(app + "有新消息啦～") }
    }

    /** 供 App 显示：桌宠网页还连着吗。 */
    fun isPetAlive(): Boolean = server?.isPetConnected() == true

    /** 试听语音（App 里调语速/音高时用）。 */
    fun testSpeak() {
        say("我是大肥鱼，这样说话听得清吗？")
    }

    /** 重载桌宠网页（切换形象/配色后调用，网页会带着新配置重连）。 */
    fun reloadPet() {
        server?.log("重载桌宠网页（换形象/配色）")
        handler.post {
            try { web_?.reload() } catch (_: Exception) {}
        }
    }

    /* ================= P2 结束 ================= */

    /* ================= P1 结束 ================= */

    private fun speakAloud(text: String) {
        if (text.isBlank()) return
        server?.log("朗读走 " + (if (ttsReady) "系统TTS" else "网页TTS"))
        val p = getSharedPreferences("pet", Context.MODE_PRIVATE)
        val rate = p.getFloat("tts_rate", 1f)
        val pitch = p.getFloat("tts_pitch", 1f)
        if (ttsReady) {
            try {
                tts?.setSpeechRate(rate)
                tts?.setPitch(pitch)
                tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "pet")
                return
            } catch (_: Exception) {
            }
        }
        val esc = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")
        val js = "(function(){try{var u=new SpeechSynthesisUtterance('" + esc + "');" +
            "u.lang='zh-CN';u.rate=" + rate + ";u.pitch=" + pitch + ";" +
            "window.speechSynthesis.cancel();window.speechSynthesis.speak(u);}catch(e){}})()"
        // 实测安卓 WebView 没有 speechSynthesis（typeof === "undefined"），先探测再说
        try {
            web_?.evaluateJavascript("typeof window.speechSynthesis") { r ->
                if (r == null || r.contains("undefined")) {
                    server?.log("网页不支持 speechSynthesis，本次朗读无法出声（等系统 TTS 恢复）")
                } else {
                    web_?.evaluateJavascript(js, null)
                }
            }
        } catch (_: Exception) {
        }
    }

    /** 让桌宠说一句：气泡 + 动作（上游）+ 本地朗读（TTS）。 */
    private fun say(text: String, actions: List<String> = emptyList()) {
        server?.log("说 -> " + text.take(80) + " (ttsReady=" + ttsReady + ")")
        server?.sendSay(text, actions)
        showBubble(text)
        speakAloud(text)
        if (!ttsReady && !ttsWarned) {
            ttsWarned = true
            handler.post {
                server?.sendSay("（没找到可用的语音引擎，我先用文字陪你～）", emptyList())
            }
        }
    }

    /** 用户说的话（打字或语音）→ 交给 Brain → 让桌宠说出来。 */
    private fun handleUserText(text: String) {
        if (text.isBlank()) return
        val srv = server ?: return
        lastUserAt = System.currentTimeMillis()
        srv.log("用户说: " + text.take(80))
        srv.sendThinking(true)
        Thread({
            val t0 = System.currentTimeMillis()
            val reply = try {
                brain.ask(text)
            } catch (e: Exception) {
                "出错了：" + e.javaClass.simpleName
            }
            val cost = System.currentTimeMillis() - t0
            handler.post({
                server?.log("模型返回(" + cost + "ms): " + (reply ?: "null").take(100))
                server?.sendThinking(false)
                if (!reply.isNullOrBlank()) say(reply) else server?.log("回复为空，不发气泡")
            })
        }, "brain").start()
    }

    /** 长按悬浮按钮 = 语音输入（走安卓系统识别）。 */
    private fun startVoice() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            say("先去 App 里给我麦克风权限吧～")
            return
        }
        try {
            voice?.destroy()
            val sr = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
            voice = sr
            sr.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    say("我在听…")
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: android.os.Bundle?) {}
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}

                override fun onError(error: Int) {
                    sr.destroy(); voice = null
                    say("没听清～再长按我一下？")
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
            say("语音没起来…（${e.javaClass.simpleName}）")
        }
    }

    /** 桌宠圆形按钮上面那个 🎤，点一下就开始语音说话。 */
    private fun addMicButton(wm: WindowManager, base: WindowManager.LayoutParams) {
        val dm = resources.displayMetrics
        val size = (dm.density * 40).toInt()
        val mp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = base.x + (base.width - size) / 2
            y = (base.y - size - (dm.density * 8).toInt()).coerceAtLeast(0)
        }
        // 🎤 按钮已去掉：安卓系统识别在你的手机上必然报错（日志里每次都是"没听清"），
        // 语音改由 💬 输入条里输入法自带的麦克风负责。
        mic_ = null
        micParams_ = null

        // 💬 悬浮按钮已移除：双击桌宠即可打开对话框（少一个钮更干净）
    }

    /** 主窗口每次变动后，把按钮重新提到最上层，否则会被全屏窗口压住点不到。 */
    private fun raiseButtons() {
        val wm = wm_ ?: return
        try { btn_?.let { v -> wm.removeView(v); wm.addView(v, btnParams_) } } catch (_: Exception) {}
        try { mic_?.let { v -> wm.removeView(v); wm.addView(v, micParams_) } } catch (_: Exception) {}
        try { chat_?.let { v -> wm.removeView(v); wm.addView(v, chatParams_) } } catch (_: Exception) {}
    }

    /** 让 🎤 跟着圆按钮走（收起时一起隐藏）。 */
    private fun syncMic() {
        val wm = wm_ ?: return
        val p = btnParams_ ?: return
        val dm = resources.displayMetrics
        var topY = p.y
        // 🎤 已在 v1.6 移除（系统识别在你手机上必失败），这里不能再因为它为空就 return
        val mv = mic_
        val mp = micParams_
        if (mv != null && mp != null) {
            val size = mp.width
            mp.x = p.x + (p.width - size) / 2
            mp.y = (p.y - size - (dm.density * 8).toInt()).coerceAtLeast(0)
            mv.visibility = if (btnCollapsed) android.view.View.GONE else android.view.View.VISIBLE
            topY = mp.y
            try { wm.updateViewLayout(mv, mp) } catch (_: Exception) {}
        }
        val cv = chat_
        val cp = chatParams_
        if (cv != null && cp != null) {
            cp.x = p.x + (p.width - cp.width) / 2
            cp.y = (topY - cp.height - (dm.density * 8).toInt()).coerceAtLeast(0)
            cv.visibility = if (btnCollapsed) android.view.View.GONE else android.view.View.VISIBLE
            try { wm.updateViewLayout(cv, cp) } catch (_: Exception) {}
        }
    }

    private fun paintButton(v: TextView, gray: Boolean) {
        val dm = resources.displayMetrics
        if (btnCollapsed) {
            v.background = GradientDrawable().apply {
                cornerRadius = dm.density * 8
                setColor(0xE04759AD.toInt())
            }
            v.text = if (btnSide == 0) "\u203A" else "\u2039"
            v.textSize = 16f
            v.setTextColor(0xFFFFFFFF.toInt())
            v.alpha = 1f
            return
        }
        v.background = resources.getDrawable(
            if (gray) R.drawable.btn_use else R.drawable.btn_pet, null)
        v.text = ""
        v.alpha = 1f
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
        p.width = (dm.density * 16).toInt()
        p.height = (dm.density * 56).toInt()
        if (btnSide == 0) p.x = 0 else p.x = dm.widthPixels - p.width
        v.text = ""
        v.textSize = 13f
        v.alpha = 0.75f
        v.background = GradientDrawable().apply {
            cornerRadius = dm.density * 8
            setColor(0xE04759AD.toInt())
        }
        v.text = if (btnSide == 0) "\u203A" else "\u2039"
        v.textSize = 16f
        v.setTextColor(0xFFFFFFFF.toInt())
        try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
        syncMic()
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
        try { mic_?.let { m -> wm.removeView(m); wm.addView(m, micParams_) } } catch (_: Exception) {}
        try { chat_?.let { c -> wm.removeView(c); wm.addView(c, chatParams_) } } catch (_: Exception) {}
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
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
        try { voice?.destroy() } catch (_: Exception) {}
        voice = null
        try { input_?.let { wm_?.removeView(it) } } catch (_: Exception) {}
        input_ = null
        try { unregisterReceiver(screenRx) } catch (_: Exception) {}
        try { status_?.let { wm_?.removeView(it) } } catch (_: Exception) {}
        status_ = null
        try { bubble_?.let { wm_?.removeView(it) } } catch (_: Exception) {}
        bubble_ = null
        try { chat_?.let { wm_?.removeView(it) } } catch (_: Exception) {}
        chat_ = null
        try { mic_?.let { wm_?.removeView(it) } } catch (_: Exception) {}
        mic_ = null
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
