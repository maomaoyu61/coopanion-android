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
import android.view.View
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
 *
 * v4.8 起多了一层：**交互层**（见 addInteractionLayer）——
 * 一块只有她那么大、跟着她走、始终可触摸的透明窗口，盖在那个整屏穿透窗口之上。
 * 这样穿透模式下也能直接点她/拎她甩出去，其余区域照样正常操作手机。
 */
class PetService : Service() {

    private var web_: WebView? = null
    private var port_: Int = 0
    private var root_: FrameLayout? = null
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

    /* ---- 交互层：跟手的一小块可触摸窗口（见 addInteractionLayer） ---- */
    private var mid_: View? = null
    private var midParams_: WindowManager.LayoutParams? = null
    private var midMoved = false
    private var midDownX = 0f
    private var midDownY = 0f
    private var midDownScreenY = 0f
    private var midEndScreenY = 0f
    private var midDownAt = 0L
    private var lastVp = 0f
    private var downLx = 0f
    private var downLy = 0f
    private var lastMx = 0f
    private var lastMy = 0f
    private var lastMoveAt = 0L
    private var lastJsAt = 0L
    private var lastFollowAt = 0L
    /** 拖动时用于算「手指这一步移动了多少」——必须用**原始屏幕坐标**。
     *  不能拿 (rawX - 窗口x) 去算：窗口跟着她走且有 30ms 限流，
     *  那样会形成正反馈，她一动就被多推一份，越拖越飞。 */
    private var lastScreenX = 0f
    private var lastScreenY = 0f
    private var downScreenX = 0f
    private var downScreenY = 0f
    /** 根（渲染）窗口相对屏幕的偏移：交互层的局部坐标 + 这个偏移 = 网页的页面坐标。 */
    private var pageOffX = 0
    private var pageOffY = 0
    private var lastMidLogAt = 0L
    /** 双击/长按判定（穿透模式下页面收不到真实事件，只能在这边认）。 */
    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var pendingTapCode: String? = null
    private var longPressX = 0f
    private var longPressY = 0f

    /** 单击延时处理：给双击留出判定窗口。 */
    private val tapTick = Runnable {
        val c = pendingTapCode
        pendingTapCode = null
        if (c != null) dispatchJs(c)
    }

    /** 长按（不动）→ 弹她自己的菜单。 */
    private val longPressTick = Runnable {
        if (midMoved) return@Runnable
        val pp = midParams_ ?: return@Runnable
        val sc = cssScale()
        val lx = fmt((longPressX - pp.x + pageOffX) / sc)
        val ly = fmt((longPressY - pp.y + pageOffY) / sc)
        server?.log("交互层：长按她 → 菜单")
        dispatchJs("(function(){var h=window.__dshPet;if(h&&h.menu)h.menu($lx,$ly);})()")
    }
    private var pendingLx = 0f
    private var pendingLy = 0f
    private var movePending = false

    /**
     * 所有往网页打的调用都走这里：**限流到每 16ms 最多一次**。
     *
     * 这是踩过坑之后加的：拖动时触摸事件一秒能来上百个，之前每个事件都
     * `evaluateJavascript` 一次，直接把 WebView 渲染进程压死 —— 现象是她整个
     * 从屏幕上消失、日志里 `/log` 也不再应答（服务活着但主循环卡住）。
     * 她那边本来就设了 RENDERER_PRIORITY_WAIVED（允许系统收走渲染进程），
     * 所以这种事必须从源头避免。
     */
    /** 派发前统一消毒：NaN/Infinity 绝不能进网页 —— 之前就是这样把她的视线污染成 NaN 的。 */
    private fun dispatchJs(code: String) {
        val w = web_ ?: return
        if (code.contains("NaN") || code.contains("Infinity")) {
            server?.log("⚠ 拦下一次坏坐标的注入（含 NaN/Infinity），已丢弃")
            return
        }
        lastJsAt = System.currentTimeMillis()
        try { w.evaluateJavascript(code, null) } catch (_: Exception) {}
    }

    /** 拖动中的位置：按帧节流后合批成一次 JS 调用。 */
    private val moveTick = Runnable {
        movePending = false
        val code = "(function(){var h=window.__dshPet;" +
            "if(!h||!h.can())return;" +
            "h.grabMove(${fmt(pendingLx)},${fmt(pendingLy)});})()"
        dispatchJs(code)
    }

    private fun scheduleMove() {
        if (movePending) return
        val wait = (16 - (System.currentTimeMillis() - lastJsAt)).coerceAtLeast(0)
        if (wait == 0L) {
            moveTick.run()
        } else {
            movePending = true
            handler.postDelayed(moveTick, wait)
        }
    }
    private var btnDownX = 0f
    private var btnDownY = 0f
    private var btnStartX = 0
    private var btnStartY = 0

    override fun onBind(intent: Intent?): IBinder? = null


    /**
     * 屏幕旋转：横屏（通常是全屏看视频）时把悬浮钮和状态条收起来，
     * 免得那根收集起来的胶囊竖着杵在画面里；回竖屏自动恢复。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val dm = resources.displayMetrics
        val land = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        // 钮的坐标是绝对像素，旋转后要夹回屏幕内
        for (p in listOfNotNull(btnParams_, chatParams_)) {
            p.x = p.x.coerceIn(0, (dm.widthPixels - p.width).coerceAtLeast(0))
            p.y = p.y.coerceIn(0, (dm.heightPixels - p.height).coerceAtLeast(0))
        }
        try { btn_?.let { v -> wm_?.updateViewLayout(v, btnParams_) } } catch (_: Exception) {}
        try { chat_?.let { v -> wm_?.updateViewLayout(v, chatParams_) } } catch (_: Exception) {}
        val hide = land && petPrefs().getBoolean("landscape_hide", true)
        btn_?.visibility = if (hide) android.view.View.GONE else android.view.View.VISIBLE
        mic_?.visibility = if (hide) android.view.View.GONE else android.view.View.VISIBLE
        chat_?.visibility = if (hide) android.view.View.GONE else android.view.View.VISIBLE
        if (hide) {
            status_?.visibility = android.view.View.GONE
        } else {
            applyStatusPlacement()
        }
        server?.log(if (hide) "横屏：已收起悬浮钮和状态条" else "竖屏：已恢复悬浮钮")
    }

    /**
     * 被系统杀掉后要求重启（START_STICKY）——安卓上最标准的保活手段。
     * 国产 ROM 尤其需要它：内存紧张时前台服务也会被清掉，靠这个能自己回来。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return android.app.Service.START_STICKY
    }

    /** 多任务界面把 App 划掉时，顺手把服务再拉起来（各家 ROM 的常规做法）。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            val i = Intent(applicationContext, PetService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (_: Exception) {
        }
        super.onTaskRemoved(rootIntent)
    }
    override fun onCreate() {
        super.onCreate()
        createChannel()
        refreshForegroundType()
        // ★ 服务重启后把语音口令恢复起来（以前漏了：重启就得手动切开关）
        syncVoice()
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) petPrefs().edit().putBoolean("tts_ok", true).apply()
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
                        petPrefs().edit().putBoolean("tts_ok", true).apply()
                    }
                } catch (e: Exception) {
                    server?.log("TTS 语言设置异常: " + e.message)
                }
            }
        }
        val s = AssetServer(this)
        s.log("=== PetService 启动 v4.7 ===")
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
                        say(LocalTalk.greeting() + "（填个 API Key 我能聊得更好哦）")
                    }
                }, 1600)
            }

            override fun onPetOther(type: String, raw: String) {
            }
        }
        attachPet(s.port)
    }

    /**
     * 渲染进程被系统收走之后的自愈：**换一个全新的 WebView** 重新加载页面。
     *
     * 为什么不能只 reload()：进程已经死了，那个 WebView 实例再也画不出东西，
     * evaluateJavascript 也永不回调。之前就是缺这一步，她一旦被系统收走渲染进程
     * 就永久消失（而且 App 日志接口也不再应答，连诊断都进不去）。
     */
    private fun recoverWebView() {
        val box = root_ ?: return
        if (port_ <= 0) return
        server?.log("重建桌宠网页：移除旧 WebView，新建一个")
        try { web_?.let { box.removeView(it) } } catch (_: Exception) {}
        try { web_?.destroy() } catch (_: Exception) {}
        val web = PetWebView(this) { want -> setWindowFocusable(want) }.apply {
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
            try {
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, false)
            } catch (_: Exception) {
            }
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    server?.log("页面: " + m.message().take(180) + " @" + m.lineNumber())
                    return true
                }
            }
        }
        web.addJavascriptInterface(JsBridge(), "AndroidPet")
        web.loadUrl("http://127.0.0.1:$port_/web/pet.html?host=window")
        try {
            box.addView(web, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT))
            web_ = web
        } catch (e: Exception) {
            server?.log("重建失败: " + e.message)
        }
        webgl2Ok = null
        lastPageError = ""
        loadRetries = 0
        followPending = false
        raiseBubble()
        raiseButtons()
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
        // 交互层喂给网页的坐标必须是"页面坐标"：根窗口在屏幕上的偏移就是页面原点
        pageOffX = params.x
        pageOffY = params.y

        port_ = port
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
            // 让桌宠的渲染进程主动礼让：系统紧张时优先保留别的 App（例如 DSH 自己的界面），
            // 否则两个 WebView 抢内存时，DSH 那边可能被系统杀掉渲染进程 → 白屏/黑屏
            try {
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, false)
            } catch (_: Exception) {
            }
            webViewClient = object : WebViewClient() {
                /**
                 * 渲染进程被系统收走（她设了 RENDERER_PRIORITY_WAIVED，这很正常）时，
                 * 必须自己把网页重新拉起来 —— 否则页面永久冻住，表现就是"她整个消失了"，
                 * 而且 evaluateJavascript 再也不回调，连诊断都做不了。
                 */
                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: android.webkit.RenderProcessGoneDetail?
                ): Boolean {
                    server?.log("⚠ 渲染进程被系统收走（didCrash=" + detail?.didCrash() + "）→ 重建桌宠网页")
                    // 渲染进程已经没了，web.reload() 是无效的 —— 必须换一个新的 WebView 才能自愈。
                    // 之前没有这一步，所以她一被系统收走渲染进程就永久消失（"头没了"的那一幕）。
                    handler.postDelayed({ recoverWebView() }, 800)
                    return true   // 返回 true：我们自己处理，别让整个 App 被一起干掉
                }
                // 她的骨架必须用 WebGL2：不支持时整块/局部渲染不出来（典型症状就是头发消失）
                override fun onPageFinished(view: WebView?, url: String?) {
                    try {
                        view?.evaluateJavascript(
                            "!!(document.createElement('canvas').getContext('webgl2'))"
                        ) { r ->
                            val ok = r != null && r.contains("true")
                            webgl2Ok = ok
                            server?.log(if (ok) "WebGL2 可用" else "WebGL2 不可用 —— 她很可能会缺部件（头发等）")
                            if (!ok) handler.post {
                                say("这台手机的 WebView 不支持 WebGL2，我可能会缺胳膊少腿…更新「Android System WebView」通常能修好")
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                override fun onReceivedError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    val d = try { error?.description?.toString() } catch (_: Exception) { null } ?: "?"
                    val u = try { request?.url?.toString() } catch (_: Exception) { null } ?: "?"
                    if (request?.isForMainFrame == true) {
                        lastPageError = d + " @ " + u
                        server?.log("页面加载失败: " + d)
                        if (loadRetries < 3) {
                            loadRetries++
                            server?.log("自动重试加载（第 " + loadRetries + " 次）")
                            handler.postDelayed({
                                try { view?.loadUrl("http://127.0.0.1:$port/web/pet.html?host=window") } catch (_: Exception) {}
                            }, 1200L * loadRetries)
                        }
                    }
                }
            }
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
        applyStatusPlacement()
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
        addInteractionLayer(wm)
    }

    /**
     * 交互层：一块**跟着她走**的透明可触摸窗口（只有她那么大，加一点余量）。
     *
     * 为什么需要它：整屏那层为了全屏漫游必须 FLAG_NOT_TOUCHABLE（见 attachPet 注释），
     * 代价是"手机能正常用"和"能摸到她"二选一。这一层把两件事拆开了：
     *
     *  - 整屏渲染层继续穿透 → 别的 App 照常操作，她的走路动画也照常播；
     *  - 交互层只占她身体那一块 → 点她、拎起来甩都在这块上完成，窗口之外全穿透。
     *
     * 位置靠网页每帧回传的包围盒（AndroidPet.pos）来跟，拖动时反过来把手指位移
     * 喂回网页（__dshPet.grab/move/release），所以拎起来是网页自己的拖拽动画。
     */
    private fun addInteractionLayer(wm: WindowManager) {
        val v = View(this).apply { setBackgroundColor(0x00000000) }
        val p = WindowManager.LayoutParams(
            (resources.displayMetrics.density * 96).toInt(),
            (resources.displayMetrics.density * 96).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = -10000
            y = 0
        }
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        v.setOnTouchListener { _, ev ->
            val p2 = midParams_
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    midMoved = false
                    server?.log("交互层 收到按下 (" + ev.rawX.toInt() + "," + ev.rawY.toInt() + ")")
                    longPressX = ev.rawX
                    longPressY = ev.rawY
                    handler.removeCallbacks(longPressTick)
                    handler.postDelayed(longPressTick, 550)
                    midDownX = ev.rawX
                    midDownY = ev.rawY
                    midDownScreenY = ev.rawY
                    midEndScreenY = ev.rawY
                    midDownAt = System.currentTimeMillis()
                    // 手指落在她身上时，网页的抓取点应当就压在这一下按的地方
                    // 手指落点 → 页面坐标（交互层局部像素 + 根窗口偏移），再按实际缩放换成 CSS 像素
                    val sc = cssScale()
                    downScreenX = ev.rawX
                    downScreenY = ev.rawY
                    lastScreenX = ev.rawX
                    lastScreenY = ev.rawY
                    downLx = p2?.let { (ev.rawX - it.x + pageOffX) / sc } ?: 0f
                    downLy = p2?.let { (ev.rawY - it.y + pageOffY) / sc } ?: 0f
                    lastMx = ev.rawX
                    lastMy = ev.rawY
                    lastMoveAt = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!midMoved &&
                        (abs(ev.rawX - midDownX) > slop || abs(ev.rawY - midDownY) > slop)) {
                        midMoved = true
                        handler.removeCallbacks(longPressTick)
                        server?.log("交互层：拎起桌宠")
                        // 让网页进入她自己的拖拽状态（暂停自由走动 + 换成被拎的姿势）。
                        // 注意：触摸事件可能一秒来上百个，**绝不能一个事件喂一次 JS** ——
                        // 之前就是那样把渲染进程压死的（表现：她整个消失 + 服务不再应答）。
                        val code = "(function(){var h=window.__dshPet;" +
                            "if(!h||!h.can())return;" +
                            "h.grab(${fmt(downLx)},${fmt(downLy)});})()"
                        dispatchJs(code)
                    }
                    if (midMoved) {
                        val pp = midParams_
                        if (pp != null) {
                            midEndScreenY = ev.rawY
                            pp.x = (pp.x + (ev.rawX - lastMx)).toInt()
                            pp.y = (pp.y + (ev.rawY - lastMy)).toInt()
                            lastMx = ev.rawX
                            lastMy = ev.rawY
                            try { wm.updateViewLayout(v, pp) } catch (_: Exception) {}
                            // 只喂手指这一步的**增量**（用原始屏幕坐标算，避免正反馈）
                            val sc2 = cssScale()
                            pendingLx = (ev.rawX - lastScreenX) / sc2
                            pendingLy = (ev.rawY - lastScreenY) / sc2
                            lastScreenX = ev.rawX
                            lastScreenY = ev.rawY
                            scheduleMove()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(moveTick)
                    movePending = false
                    if (midMoved) {
                        val sc = cssScale()
                        val pp = midParams_
                        val code = (if (ev.actionMasked == MotionEvent.ACTION_UP)
                            "(function(){var h=window.__dshPet;if(!h||!h.can())return;" +
                                "h.grabEnd(${fmt((ev.rawX - (pp?.x ?: 0) + pageOffX) / sc)},${fmt((ev.rawY - (pp?.y ?: 0) + pageOffY) / sc)});})()"
                        else
                            "(function(){var h=window.__dshPet;if(!h||!h.can())return;h.cancel();})()")
                        dispatchJs(code)
                    } else if (ev.actionMasked == MotionEvent.ACTION_UP) {
                        handler.removeCallbacks(longPressTick)
                        val dens = resources.displayMetrics.density
                        val pp = midParams_
                        val sc = cssScale()   // 注意：给网页的坐标要按 CSS 缩放换算，别用 density
                        val lx = fmt((ev.rawX - (pp?.x ?: 0) + pageOffX) / sc)
                        val ly = fmt((ev.rawY - (pp?.y ?: 0) + pageOffY) / sc)
                        val nowMs = System.currentTimeMillis()
                        val isDouble = nowMs - lastTapAt < 320 &&
                            Math.hypot((ev.rawX - lastTapX).toDouble(), (ev.rawY - lastTapY).toDouble()) < dens * 45
                        if (isDouble) {
                            // 双击：和非穿透模式保持一致 —— 走**我们自己**的输入条（showChatInput），
                            // 不是网页气泡里那个输入框。同时取消"等一下再当单击处理"的延时。
                            handler.removeCallbacks(tapTick)
                            pendingTapCode = null
                            lastTapAt = 0L
                            server?.log("交互层：双击她 → 输入框")
                            showChatInput()
                        } else {
                            // 单击：延时一点再处理，给双击留出判定窗口
                            lastTapAt = nowMs
                            lastTapX = ev.rawX
                            lastTapY = ev.rawY
                            pendingTapCode = "(function(){var h=window.__dshPet;if(!h||!h.can())return;" +
                                "h.grab($lx,$ly);h.grabEnd($lx,$ly);})()"
                            handler.postDelayed(tapTick, 200)
                            server?.log("交互层：轻点她 (" + (nowMs - midDownAt) + "ms)")
                        }
                    }
                    midMoved = false
                    true
                }
                else -> false
            }
        }
        try {
            wm.addView(v, p)
            mid_ = v
            midParams_ = p
        } catch (_: Exception) {
        }
        applyInteractionVisibility()
    }

    private fun fmt(f: Float): String {
        val r = Math.round(f * 100f) / 100f
        return r.toString()
    }

    /** 一个 CSS 像素等于多少屏幕像素（网页视口宽度对不上时退回 density）。 */
    private fun cssScale(): Float {
        val sw = resources.displayMetrics.widthPixels.toFloat()
        return if (lastVp > 1f && sw > 0f) sw / lastVp else resources.displayMetrics.density
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
                // ★ 必须不可触摸：这个气泡有 0.62 屏宽、还跟着她走，
                //   之前它把她周围一大片触摸全吃掉了（表现为"只有某些点能摸到她"）。
                //   这是纯显示的气泡，本来就不需要接收触摸。
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
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
            // 网页可能每帧都推，这里合批成每帧最多一次真正的窗口操作 ——
            // updateViewLayout 一秒钟做上百次同样会把渲染拖死。
            posX = x; posY = y; posW = w; posH = h
            if (followPending) return
            followPending = true
            handler.post(followTick)
        }

        /** 网页报一次视口宽度（CSS 像素）—— 用来把网页坐标精确换算成屏幕像素。 */
        @android.webkit.JavascriptInterface
        fun vp(w: Float) {
            if (w > 0f) lastVp = w
        }
    }

    private var posX = 0
    private var posY = 0
    private var posW = 0
    private var posH = 0
    private var followPending = false

    private val followTick = Runnable {
        followPending = false
        applyPetPos()
    }

    private var lastBx = Int.MIN_VALUE
    private var lastBy = Int.MIN_VALUE

    private fun applyPetPos() {
        val cx = posX; val cy = posY; val cw = posW; val ch = posH
        if (cw <= 0 || ch <= 0) return
        // 窗口操作限流：最快 30ms 一次（约 33fps），比她的帧率略低但完全够跟手
        val now = System.currentTimeMillis()
        if (now - lastFollowAt < 30) return
        lastFollowAt = now
        val wm = wm_ ?: return
        val dm = resources.displayMetrics
        val sw = dm.widthPixels
        val sh = dm.heightPixels
        val sc = if (lastVp > 1f) sw / lastVp else dm.density

        // ① 交互层：跟着她走，永远盖在她身上（这块是唯一接收触摸的区域）
        val mv = mid_
        val mp = midParams_
        if (mv != null && mp != null) {
            // 手指余量固定按 dp 给（跟缩放无关），窗口＝她真实像素范围＋这点余量。
            // 别按 density 去乘尺寸 —— 那会让窗口比她还大好几倍。
            val pad = (dm.density * 6).toInt()
            val w = (cw * sc).toInt() + pad * 2
            val h = (ch * sc).toInt() + pad * 2
            val x = (cx * sc).toInt() - pad
            val y = (cy * sc).toInt() - pad
            if (w > 0 && h > 0) {
                val mx = x.coerceIn(0, (sw - w).coerceAtLeast(0))
                val my = y.coerceIn(0, (sh - h).coerceAtLeast(0))
                if (mx != mp.x || my != mp.y || w != mp.width || h != mp.height) {
                    mp.x = mx
                    mp.y = my
                    mp.width = w
                    mp.height = h
                    try { wm.updateViewLayout(mv, mp) } catch (_: Exception) {}
                    // 自证日志：把交互层的真实矩形写出来（诊断"死区多大"时直接看这行）
                    val nw = System.currentTimeMillis()
                    if (nw - lastMidLogAt > 3000) {
                        lastMidLogAt = nw
                        val screeN = "屏幕 " + sw + "x" + sh
                        server?.log("交互层 rect x=" + mx + " y=" + my + " " + w + "x" + h +
                            " (她 " + (cw * sc).toInt() + "x" + (ch * sc).toInt() + " @" + (cx * sc).toInt() + "," + (cy * sc).toInt() + ", sc=" + (Math.round(sc * 100f) / 100f) + ") " + screeN)
                    }
                }
            }
        }

        // ② 原生气泡：贴她头顶
        val b = bubble_ ?: return
        val p = bubbleParams_ ?: return
        if (b.visibility != android.view.View.VISIBLE) return
        val px = (cx * sc).toInt()
        val py = (cy * sc).toInt()
        val pw = (cw * sc).toInt()
        val bh = if (b.height > 0) b.height else (dm.density * 64f).toInt()
        val bx = (px + pw / 2 - p.width / 2).coerceIn(0, (sw - p.width).coerceAtLeast(0))
        // 气泡贴她头顶；状态条是固定位置（不跟随），所以这里只算气泡
        val bubbleTop = py - (dm.density * 8f).toInt()
        val by = (bubbleTop - bh).coerceAtLeast(0)
        if (Math.abs(bx - lastBx) < 1 && Math.abs(by - lastBy) < 1) return
        lastBx = bx
        lastBy = by
        p.x = bx
        p.y = by
        try { wm.updateViewLayout(b, p) } catch (_: Exception) {}
    }

    /** 交互层只在"触摸穿透"时上岗：不穿透时整屏那层本来就归她，再加一层会互相抢。 */
    private fun applyInteractionVisibility() {
        val v = mid_ ?: return
        v.visibility = if (passthrough) View.VISIBLE else View.GONE
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
    private var loadRetries = 0
    private var webgl2Ok: Boolean? = null
    private var petRect_ = "未检测"
    private var animOk_: Boolean? = null
    private var voice_: PetVoice? = null
    private var screenOn_ = true
    private var dshState_ = ""
    private var lastPageError = ""
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
            (dm.widthPixels * 0.45f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                // ★ 同气泡：纯显示的陪伴状态条，别吃触摸（它会跟着她，挡住交互层）
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 绝对定位（不跟着她走！跟着走=每秒几十次窗口重排=闪烁）
            gravity = Gravity.TOP or Gravity.START
            x = ((dm.widthPixels - (dm.widthPixels * 0.45f)) / 2).toInt()
            y = dm.heightPixels - (dm.density * 96).toInt()
        }
        // 状态条拖动：拖到任意位置（记住），轻点一下收起（不挡屏幕）
        val slopS = ViewConfiguration.get(this).scaledTouchSlop
        var sdX = 0f; var sdY = 0f; var sMoved = false
        v.setOnTouchListener { view, ev ->
            val pp = statusParams_
            if (pp == null) false else when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sdX = ev.rawX; sdY = ev.rawY; sMoved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(ev.rawX - sdX) > slopS || abs(ev.rawY - sdY) > slopS) sMoved = true
                    if (sMoved) {
                        pp.x = (pp.x + (ev.rawX - sdX)).toInt()
                        pp.y = (pp.y + (ev.rawY - sdY)).toInt()
                        sdX = ev.rawX; sdY = ev.rawY
                        try { wm.updateViewLayout(view, pp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!sMoved) {
                        view.visibility = android.view.View.GONE
                        petPrefs().edit().putBoolean("status_hidden", true).apply()
                        server?.log("状态条已被手动收起（设置里有「恢复显示状态条」按钮）")
                        android.widget.Toast.makeText(
                            this@PetService,
                            "状态条已收起，想恢复去「④ 陪伴 → 恢复显示状态条」",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        petPrefs().edit()
                            .putString("status_pos", "custom")
                            .putInt("status_x", pp.x)
                            .putInt("status_y", pp.y)
                            .apply()
                        server?.log("状态条位置已记住: " + pp.x + "," + pp.y)
                    }
                    true
                }
                else -> false
            }
        }
        try { wm.addView(v, p) } catch (_: Exception) {}
        status_ = v
        statusParams_ = p
    }

    private fun showStatus(t: String) {
        val v = status_ ?: return
        // 用户手动点收起了就别再弹出来（设置里切换位置可恢复）
        if (petPrefs().getBoolean("status_hidden", false)) return
        if (v.text == t) return
        // ★ 只原地改文字：绝不能 removeView+addView（那是每秒一次的闪烁源）
        v.text = t
        v.visibility = android.view.View.VISIBLE
    }

    /** 按设置把状态条放到固定位置：脚底（屏幕底部居中，默认）/ 头顶 / 不显示。 */
    fun applyStatusPlacement() {
        val wm = wm_ ?: return
        val v = status_ ?: return
        val p = statusParams_ ?: return
        val mode = petPrefs().getString("status_pos", "feet")
        if (petPrefs().getBoolean("status_hidden", false)) {
            v.visibility = android.view.View.GONE
            return
        }
        if (mode == "off") {
            v.visibility = android.view.View.GONE
            return
        }
        val dm = resources.displayMetrics
        p.gravity = Gravity.TOP or Gravity.START
        when (mode) {
            "custom" -> {
                p.x = petPrefs().getInt("status_x", (dm.widthPixels * 0.27f).toInt())
                p.y = petPrefs().getInt("status_y", dm.heightPixels - (dm.density * 96).toInt())
            }
            "head" -> {
                p.x = ((dm.widthPixels - p.width) / 2).coerceAtLeast(0)
                p.y = dimen("status_bar_height") + (dm.density * 6).toInt()
            }
            else -> {
                p.x = ((dm.widthPixels - p.width) / 2).coerceAtLeast(0)
                p.y = dm.heightPixels - (dm.density * 96).toInt()
            }
        }
        try { wm.removeView(v) } catch (_: Exception) {}
        try { wm.addView(v, p) } catch (_: Exception) {}
        v.visibility = android.view.View.VISIBLE
    }

    private fun hideStatus() {
        status_?.visibility = android.view.View.GONE
    }

    private val dshPoll = object : Runnable {
        override fun run() {
            val on = screenOn_
            val active = dshState_ == "working" || dshState_ == "thinking"
            // 息屏时完全停掉（没人看状态条，没必要每秒唤醒）
            if (on && petPrefs().getBoolean("dsh_link", true)) {
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
            // 干活时 1 秒一刷；空闲时 8 秒；息屏时 15 秒才检查一次
            handler.postDelayed(this, if (!on) 15000L else if (active) 1000L else 8000L)
        }
        }

    private fun applyDshState(st: String, text: String) {
        dshState_ = st
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
                        val line = if (brain.configured()) brain.ask("（现在没人跟你说话，你自己待着。请主动跟主人说一句话，20字以内，符合你的人设）") else null
                        handler.post { say(line ?: LocalTalk.idleLine()) }
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
                val line = if (brain.configured()) brain.ask("（主人刚摸了摸你的头。请用一句话回应，20字以内，语气亲近）") else null
                handler.post { say(line ?: LocalTalk.patReply()) }
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
    /** 心跳里顺带探一次页面：动画是否还在跑、她还在不在、她多大。 */
    private fun probePage() {
        val w = web_ ?: return
        try {
            // 先读上一次探测的结果（rAF 是异步的，隔一拍才拿得到）
            w.evaluateJavascript("window.__dshProbe || 'none'") { r ->
                animOk_ = r != null && r.contains("ok")
            }
            // 再下一个新探测 + 取她的位置尺寸
            w.evaluateJavascript(
                "(function(){window.__dshProbe='pending';" +
                    "try{requestAnimationFrame(function(){window.__dshProbe='ok';});}catch(e){}" +
                    "var p=document.getElementById('pet');if(!p)return 'nopet';" +
                    "var r=p.getBoundingClientRect();" +
                    "return Math.round(r.left)+','+Math.round(r.top)+' size '+Math.round(r.width)+'x'+Math.round(r.height);})()"
            ) { r2 ->
                val s = (r2 ?: "").trim('"')
                petRect_ = if (s.contains("nopet")) "她不存在!" else s
            }
        } catch (_: Exception) {
        }
    }

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
            probePage()
            handler.postDelayed(this, 8000)
        }
    }

    /* ================= P2：省电 / 试听 / 重载 ================= */

    /** 省电：息屏时暂停网页渲染，亮屏恢复。 */
    private val screenRx = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (!petPrefs().getBoolean("power_save", true)) return
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn_ = false
                    try { voice_?.stop() } catch (_: Exception) {}
                    try { web_?.onPause() } catch (_: Exception) {}
                    // 暂停的 WebView 有时会画黑盖住下面 → 一并隐藏
                    try { web_?.visibility = android.view.View.INVISIBLE } catch (_: Exception) {}
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn_ = true
                    try { syncVoice() } catch (_: Exception) {}
                    try { web_?.visibility = android.view.View.VISIBLE } catch (_: Exception) {}
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

    /** 给别的类写日志用（server 是私有的）。 */
    /**
     * 重设前台服务类型。
     * Android 11+ 规定：前台服务要访问麦克风，必须在启动时声明 microphone 类型，
     * 否则语音识别会一直报 code=9（ERROR_INSUFFICIENT_PERMISSIONS）。
     */
    private fun refreshForegroundType() {
        val wantMic = petPrefs().getBoolean("voice_cmd", false)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                val t = if (wantMic)
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                startForeground(NOTIF_ID, buildNotification(), t)
            } else if (android.os.Build.VERSION.SDK_INT >= 30 && wantMic) {
                startForeground(NOTIF_ID, buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
        } catch (e: Exception) {
            try { startForeground(NOTIF_ID, buildNotification()) } catch (_: Exception) {}
        }
    }

    fun logLine(s: String) {
        try { server?.log(s) } catch (_: Exception) {}
    }

    /** 按设置和息屏状态，决定语音口令听不听。 */
    fun syncVoice() {
        val on = petPrefs().getBoolean("voice_cmd", false)
        refreshForegroundType()          // ★ 先声明（或不声明）麦克风类型，否则识别必报 code=9
        val want = on && screenOn_
        if (want) {
            if (voice_ == null) voice_ = PetVoice(this)
            voice_?.start()
            server?.log("语音口令：已开始监听（口令词：大肥鱼）")
        } else {
            voice_?.stop()
        }
    }

    /** 供诊断：最近日志（含页面 console）。 */
    fun logTail(n: Int): String = try { server?.logTail(n) ?: "(无)" } catch (e: Exception) { "(读不到)" }

    /** 供诊断：桌宠网页当前状态。 */

    /** 动作标记自检：点一下就能看出【】/< 两种标记有没有生效。 */
    fun testActions() {
        say("【跳】动作测试成功！<眨眼>这是第二个气泡")
        server?.log("已发送动作测试（【跳】+ <眨眼>）")
    }

    fun pageInfo(): String {
        val w = web_ ?: return "网页: 未创建"
        return try {
            "网页: " + (w.url ?: "?") + " | 进度 " + w.progress + "%" +
                " | 她的位置尺寸: " + petRect_ +
                " | 她的动画: " + (animOk_?.let { if (it) "正常" else "停了!" } ?: "未知") +
                " | WebGL2: " + (webgl2Ok?.let { if (it) "可用" else "不可用!" } ?: "未检测") +
                (if (lastPageError.isNotEmpty()) " | 最近错误: " + lastPageError else "")
        } catch (e: Exception) {
            "网页: 读不到"
        }
    }

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
    /** 让她说一句（气泡 + 朗读）。语音口令那边也要用，所以是公开的。 */
    fun say(text: String, actions: List<String> = emptyList()) {
        server?.log("说 -> " + text.take(80) + " (ttsReady=" + ttsReady + ")")
        server?.sendSay(text, actions)
        // 气泡样式：page = 只显示网页那个白气泡；native/both = 也画原生气泡
        if (petPrefs().getString("bubble_mode", "page") == "native" || petPrefs().getString("bubble_mode", "page") == "both") showBubble(text)
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
        // 先看是不是"让她操作手机"的指令（需要用户显式允许 + 无障碍已开）
        if (petPrefs().getBoolean("a11y_control", false) && PetA11yService.alive()) {
            try {
                val r = PetCmd.handle(text)
                if (r != null) {
                    lastUserAt = System.currentTimeMillis()
                    say(r)
                    return
                }
            } catch (_: Exception) {
            }
        }
        // 带了口令词但单步指令没命中 → 交给 AI 边看屏幕边多步执行（需显式开启）
        if (petPrefs().getBoolean("ai_agent", false) && PetA11yService.alive()) {
            val task = PetCmd.stripWake(text)
            if (task != null && task.length >= 2) {
                lastUserAt = System.currentTimeMillis()
                say("好，我来试试…")
                Thread({
                    val r = try {
                        PetAgent.run(task, { sys, usr -> brain.agentStep(sys, usr) }) { step -> handler.post { say(step) } }
                    } catch (e: Exception) { "我卡住了…" }
                    handler.post { say(r) }
                }, "petagent").start()
                return
            }
        }
        // 没填 API Key（或断网）时用离线台词库，别让她只能发呆
        if (!brain.configured()) {
            lastUserAt = System.currentTimeMillis()
            say(LocalTalk.reply(text))
            return
        }
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
        applyInteractionVisibility()
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
        try { voice_?.stop() } catch (_: Exception) {}
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
