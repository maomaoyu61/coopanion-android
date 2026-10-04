package com.maomaoyu.coopanionpet

import android.content.Context
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

/**
 * 应用内的迷你服务器：静态资源（上游桌宠网页）+ 上游需要的那几个接口 + 一个极简 WebSocket。
 *
 * 为什么需要它：上游 pet.html 用的是 /web/ 绝对路径，且装扮页通过
 *   POST /api/skin  {skin}     保存形象/配色
 *   POST /api/prefs {theme}    保存明暗
 * 而桌宠本体是通过 WebSocket /socket?role=pet 收到 {"t":"init", skin:{...}} 才应用肤色的。
 * 这里把这套最小实现补上，装扮才能真正"保存并生效"。
 */
class AssetServer(private val ctx: Context) {

    private var serverSocket: ServerSocket? = null
    @Volatile
    private var petOut: java.io.OutputStream? = null
    private var petSock: java.net.Socket? = null

    /**
     * 发送专用线程。
     * 关键：Android 禁止在主线程做网络 I/O —— 之前所有 say/prefs 都在主线程直接写 socket，
     * 每次都被 NetworkOnMainThreadException 打回，于是"消息永远发不出去 + 连接反复重连"。
     */
    private val sendExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "pet-send").apply { isDaemon = true }
    }
    private var walkSeq = 0
    private var walkSide = false
    private var saySeq = 0
    private val logs = ArrayDeque<String>()
    private var lastDropLogAt = 0L

    /** 未连接时丢消息也要留个痕迹，否则又变成"静默失效"。 */
    private fun noPet(what: String) {
        val now = System.currentTimeMillis()
        if (now - lastDropLogAt > 10000) {
            lastDropLogAt = now
            log("桌宠未连接，已丢弃: " + what.take(30))
        }
    }

    /** 由 Service 提供：在桌宠网页里执行一段 JS（用于排错）。 */
    var onEval: ((String) -> Unit)? = null

    /** 记一条日志（同时供 /log 接口读取，方便在电脑/容器里排错）。 */
    /** 给诊断用：最近 n 条日志（含页面 console）。 */
    fun logTail(n: Int): String = synchronized(logs) {
        if (logs.isEmpty()) "(无)" else logs.toList().takeLast(n).joinToString(" | ")
    }

    /**
     * 老 WebView（Chromium < 85）不认 ??= / ||= / &&=，会直接抛
     * "Unexpected token '='" → 整个 JS 模块解析失败 → 她的模型和 WebSocket 全都不启动。
     * 这里在服务端把这三个运算符降级成等价的老语法（只处理简单标识符，安全）。
     */
    private fun transpileJs(src: String): String {
        var s = src
        val id = "((?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Za-z_$][A-Za-z0-9_$]*)"
        s = s.replace(Regex("$id\\s*\\?\\?=")) { m -> m.groupValues[1] + " = (" + m.groupValues[1] + " !== null && " + m.groupValues[1] + " !== void 0) ? " + m.groupValues[1] + " : " }
        s = s.replace(Regex("$id\\s*\\|\\|=")) { m -> m.groupValues[1] + " = " + m.groupValues[1] + " || " }
        s = s.replace(Regex("$id\\s*&&=")) { m -> m.groupValues[1] + " = " + m.groupValues[1] + " && " }
        return s
    }

    fun log(line: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        synchronized(logs) {
            logs.addLast("$ts $line")
            while (logs.size > 300) logs.removeFirst()
        }
    }

    /** 给桌宠发外观/音效设置（网页支持 scale 与 sound）。 */
    fun sendPrefs(scale: Double? = null, sound: Boolean? = null) {
        val sb = StringBuilder("{\"t\":\"prefs\"")
        if (scale != null) sb.append(",\"scale\":").append(scale)
        if (sound != null) sb.append(",\"sound\":").append(sound)
        sb.append("}")
        log("发设置 -> " + sb)
        sendJson(sb.toString())
    }

    private fun sendJson(json: String) {
        val o = petOut
        if (o == null) { noPet("send"); return }
        try {
            sendFrame(o, 0x1, json.toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {
        }
    }

    /** 桌宠网页的 socket 还连着吗（省电暂停后可能已断开）。 */
    fun isPetConnected(): Boolean = petOut != null

    /** 关掉她身上那两个网页悬浮图标（chat / voice）。 */
    fun hideHoverButtons() {
        sendJson("{\"t\":\"prefs\",\"hoverButtons\":[]}")
    }

    private fun logText(): String = synchronized(logs) { logs.joinToString("\n") }

    /** 桌宠发来的事件（打字、摸它、上线…）交给上层处理。 */
    interface PetEvents {
        fun onPetText(text: String)
        fun onPetControl(action: String)
        fun onPetTouch()
        fun onPetHello()
        fun onPetOther(type: String, raw: String)
    }

    @Volatile
    var events: PetEvents? = null

    /** 让桌宠说话（可带动作）。 */
    fun sendSay(text: String, actions: List<String> = emptyList()) {
        val out = petOut
        if (out == null) { noPet("send"); return }
        saySeq++
        // 用上游同款规则解析【动作】/<动作> 标记，生成带 anchors 的 beats
        val beats = PetScript.beatsJson(text)
        if (actions.isNotEmpty()) {
            try {
                val b0 = beats.optJSONObject(0)
                if (b0 != null) {
                    val a0 = b0.optJSONArray("actions") ?: JSONArray()
                    for (a in actions) a0.put(a)
                    b0.put("actions", a0)
                }
            } catch (_: Exception) {
            }
        }
        val msg = JSONObject().apply {
            put("t", "say")
            put("id", "s" + saySeq)
            put("beats", beats)
        }
        rawToPet(out, msg.toString())
    }

    /** 提问（own=true 时气泡里会出现输入框，用户可以打字）。 */
    fun sendAsk(question: String, options: List<String>, own: Boolean) {
        val out = petOut
        if (out == null) { noPet("send"); return }
        saySeq++
        val msg = JSONObject().apply {
            put("t", "ask")
            put("id", "a$saySeq")
            put("question", question)
            put("options", JSONArray(options))
            put("own", own)
        }
        rawToPet(out, msg.toString())
    }

    /** 头顶转圈（思考中）。 */
    fun sendThinking(on: Boolean) {
        val out = petOut
        if (out == null) { noPet("send"); return }
        val msg = JSONObject().apply { put("t", "thinking"); put("on", on) }
        rawToPet(out, msg.toString())
    }

    private fun rawToPet(out: java.io.OutputStream, json: String) {
        // 必须在后台线程写，见 sendExec 的注释
        try {
            sendExec.execute {
                try {
                    synchronized(out) { sendText(out, json) }
                } catch (e: Exception) {
                    log("⚠ 发给桌宠失败(" + e.javaClass.simpleName + ") → 关掉连接让它自动重连")
                    petOut = null
                    try { petSock?.close() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun handleIncoming(msg: String) {
        try {
            val o = JSONObject(msg)
            log("收到 <- " + msg.take(120))
            when (o.optString("t")) {
                "text" -> o.optString("text").takeIf { it.isNotBlank() }?.let { events?.onPetText(it) }
                "commit" -> {
                    val s = o.optString("text")
                    if (s.isNotBlank()) events?.onPetText(s) else events?.onPetOther("commit", msg)
                }
                "touch" -> events?.onPetTouch()
                "control" -> events?.onPetControl(o.optString("action"))
                "hello" -> events?.onPetHello()
                else -> events?.onPetOther(o.optString("t"), msg)
            }
        } catch (_: Exception) {
        }
    }

    /** 让桌宠播放"走路"动画（窗口移动由 PetService 负责）。 */
    fun petWalk(run: Boolean = false) {
        val out = petOut
        if (out == null) { noPet("send"); return }
        walkSeq++
        walkSide = !walkSide
        val to = if (walkSide) 0.12 else 0.88
        val json = "{\"t\":\"walk\",\"id\":\"w$walkSeq\",\"to\":$to,\"run\":$run}"
        try {
            synchronized(out) { sendText(out, json) }
        } catch (e: Exception) {
            log("⚠ 发给桌宠失败(" + e.javaClass.simpleName + ") → 关掉连接让它自动重连")
            petOut = null
            // 关键：必须把连接关掉，网页端的 onclose 才会触发自动重连；
            // 只置空 petOut 会让双方都以为还连着 → 永久静默
            try { petSock?.close() } catch (_: Exception) {}
        }
    }
    var port: Int = 0
        private set

    private val prefs by lazy { ctx.getSharedPreferences("pet", Context.MODE_PRIVATE) }

    fun start() {
        val ss = openSocket()
        port = ss.localPort
        serverSocket = ss
        Thread({
            while (!ss.isClosed) {
                try {
                    val client = ss.accept()
                    try { client.soTimeout = 8000 } catch (_: Exception) {}
                    Thread { handle(client) }.start()
                } catch (e: Exception) {
                    break
                }
            }
        }, "asset-server").start()
    }

    /** 固定端口，origin 稳定，装扮选择才能跨次启动保留。 */
    private fun openSocket(): ServerSocket {
        for (p in intArrayOf(8731, 8732, 8733, 8734)) {
            try {
                return ServerSocket(p, 32, InetAddress.getByName("127.0.0.1"))
            } catch (_: Exception) {
            }
        }
        return ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private fun handle(sock: Socket) {
        try {
            val input = sock.getInputStream()
            val out = sock.getOutputStream()
            val reader = input.bufferedReader(Charsets.ISO_8859_1)
            val requestLine = reader.readLine() ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore('?')
            val query = parts[1].substringAfter('?', "")

            // WebSocket 升级
            if (headers["upgrade"]?.lowercase() == "websocket") {
                websocket(sock, out, headers["sec-websocket-key"], query)
                return
            }

            when {
                path == "/api/skin" && method == "POST" -> {
                    val body = readBody(reader, headers)
                    prefs.edit().putString("skin", body).apply()
                    reply(out, 200, "application/json", "{\"ok\":true}")
                }
                path == "/api/skin" -> {
                    val skin = prefs.getString("skin", null)
                    reply(out, 200, "application/json", skin ?: "null")
                }
                path == "/api/prefs" && method == "POST" -> {
                    val body = readBody(reader, headers)
                    prefs.edit().putString("prefs", body).apply()
                    reply(out, 200, "application/json", "{\"ok\":true}")
                }
                path == "/api/prefs" -> {
                    val p = prefs.getString("prefs", null)
                    reply(out, 200, "application/json", p ?: "{}")
                }
                path == "/api/avatar" -> reply(out, 404, "text/plain", "no avatar")
                path == "/debug/js" -> {
                    // 注意：查询串在 parts[1]（请求行是 "GET /path?query HTTP/1.1"），
                    // 之前从 parts[2] 取，那是 HTTP 版本号，所以永远是空
                    val raw = parts.getOrNull(1)?.substringAfter("code=", "") ?: ""
                    val code = if (raw.isEmpty()) "" else try {
                        java.net.URLDecoder.decode(raw, "UTF-8")
                    } catch (_: Exception) { raw }
                    log("执行JS: " + code.take(120))
                    onEval?.invoke(code)
                    reply(out, 200, "text/plain", "ok")
                }
                path == "/a11y/status" -> reply(out, 200, "text/plain",
                    if (PetA11yService.alive()) "on" else "off")
                path.startsWith("/a11y/") -> {
                    val s = PetA11yService.instance
                    if (s == null) {
                        reply(out, 200, "text/plain; charset=utf-8", "无障碍服务未开启")
                    } else {
                        fun q(k: String): String {
                            for (kv in query.split("&")) {
                                val i = kv.indexOf('=')
                                if (i > 0 && kv.substring(0, i) == k) {
                                    return try {
                                        java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8")
                                    } catch (_: Exception) { kv.substring(i + 1) }
                                }
                            }
                            return ""
                        }
                        val r = when (path) {
                            "/a11y/dump" -> s.dump()
                            "/a11y/click" -> if (s.clickText(q("text"))) "ok" else (s.lastRefusal ?: "没找到该文字")
                            "/a11y/tap" -> if (s.tap(q("x").toIntOrNull() ?: 0, q("y").toIntOrNull() ?: 0)) "ok" else (s.lastRefusal ?: "失败")
                            "/a11y/swipe" -> if (s.swipe(q("x1").toIntOrNull() ?: 0, q("y1").toIntOrNull() ?: 0,
                                q("x2").toIntOrNull() ?: 0, q("y2").toIntOrNull() ?: 0)) "ok" else "失败"
                            "/a11y/global" -> {
                                val w = when (q("which")) { "home" -> 2; "recents" -> 3; "notif" -> 4; else -> 1 }
                                if (s.global(w)) "ok" else "失败"
                            }
                            "/a11y/type" -> if (s.typeText(q("text"))) "ok" else (s.lastRefusal ?: "没有聚焦的输入框（或密码框已跳过）")
                            "/a11y/open" -> if (s.openApp(q("app"))) "ok" else (s.lastRefusal ?: "没找到该应用")
                            else -> "未知接口（dump/click/tap/swipe/global/type/open）"
                        }
                        reply(out, 200, "text/plain; charset=utf-8", r)
                    }
                }
                path == "/log" -> reply(out, 200, "text/plain; charset=utf-8",
                    logText() + "\n\n-- petOut=" + (petOut != null) + " --")
                path == "/dress" -> serveAsset(out, "/web/dress.html")
                else -> serveAsset(out, if (path == "/" || path.isEmpty()) "/web/pet.html" else path)
            }
        } catch (_: Exception) {
        }
    }

    private fun readBody(reader: java.io.BufferedReader, headers: Map<String, String>): String {
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0) return ""
        val buf = CharArray(len)
        var read = 0
        while (read < len) {
            val n = reader.read(buf, read, len - read)
            if (n <= 0) break
            read += n
        }
        return String(buf, 0, read)
    }

    private fun reply(out: java.io.OutputStream, code: Int, mime: String, body: String) {
        val data = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code OK\r\nContent-Type: $mime\r\nContent-Length: ${data.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(data)
        out.flush()
    }

    private fun serveAsset(out: java.io.OutputStream, path: String) {
        val assetPath = path.trimStart('/')
        try {
            val stream: InputStream = ctx.assets.open(assetPath)
            var data = stream.use { it.readBytes() }
            // 老 WebView 兼容：把 ??= / ||= / &&= 降级成老语法，否则整页 JS 都不跑
            if (path.endsWith(".js")) {
                val fixed = transpileJs(String(data, Charsets.UTF_8))
                if (fixed != String(data, Charsets.UTF_8)) {
                    data = fixed.toByteArray(Charsets.UTF_8)
                    log("JS 兼容处理: " + assetPath)
                }
            }
            // pet-app.js：把上游网页内部的控制器交出来，原生交互层才能用她自己那套
            // 抓取/甩出逻辑（不搬窗口，所以有动作动画）。同样不碰上游仓库，只在服务时注入。
            if (path.endsWith("/pet-app.js")) {
                val js = String(data, Charsets.UTF_8)
                // ① 行为模式可以由原生端给初值
                val a = js.replaceFirst(
                    "  roam: prefs.roam,",
                    "  roam: ((window.__dshPet && window.__dshPet.roam) || prefs.roam),")
                // ② 消毒守卫：pet.look 一旦被 NaN 污染，lerp 会让它永久变 NaN，
                //    而大肥鱼的 draw 用 look 算头的形变 → 头顶/头发/脸全被画到画布外（"头不见了"）。
                //    这里包一层 ctl.render，每帧绘制前把非有限值归零，并只报一次日志钉住污染源。
                val b = a.replaceFirst(
                    "function applyPrefs(p) {",
                    "function __dshLookGuard(){if(window.__lookGuard)return;window.__lookGuard=1;" +
                        "try{" +
                        "var bad=function(v){return typeof v!=='number'||!isFinite(v);};" +
                        "var fix=function(p){if(!p)return null;var b=null;" +
                        "if(p.look&&bad(p.look[1])){p.look[1]=0;b='look[1]';}" +
                        "if(p.look&&bad(p.look[0])){p.look[0]=0;b='look[0]';}" +
                        "if(p.glance&&bad(p.glance[1])){p.glance[1]=0;b='glance[1]';}" +
                        "if(p.glance&&bad(p.glance[0])){p.glance[0]=0;b='glance[0]';}" +
                        "if(b&&!window.__lookWarned){window.__lookWarned=1;" +
                        "console.error('[lookGuard] '+b+' 曾是 NaN，已归零（头的形变参数就是它污染的）');}" +
                        "return b;};" +
                        "if(ctl&&ctl.pet)fix(ctl.pet);" +
                        "if(ctl&&ctl.render){var _r=ctl.render;ctl.render=function(){fix(ctl.pet);return _r.apply(ctl,arguments);};" +
                        "console.log('[lookGuard] 已接管 ctl.render（每帧消毒）');}" +
                        "else console.error('[lookGuard] ctl.render 不可用，消毒未启用');" +
                        "}catch(e){console.error('[lookGuard] 安装失败 '+e.message);}}" +
                        "function applyPrefs(p) { __dshLookGuard();")
                // ③ 把 ctl 交出来并立刻安装守卫
                val c = b.replaceFirst(
                    "const ctl = createPet(",
                    "window.__dshPet = window.__dshPet || {}; const ctl = createPet(")
                // ★ 拖动时**取消舞台边界**：上游把拖动中的横向位置夹在 minX()/maxX() 里
                //   （留出她自身宽度，所以贴不到左右边），纵向还压了 floorY-245S，拎不高。
                //   这里在每帧绘制前把夹取结果放回去，让她能拖到屏幕任意角落；
                //   拖动中的视觉位置是 pet.dx/dy，所以只有松手落地才需要这个。
                val c2 = c.replaceFirst(
                    "function applyPrefs(p) {",
                    "function __dshFreeDrag(){" +
                        "if(window.__freeDrag)return;window.__freeDrag=1;" +
                        "function unfix(q,bd){" +
                        "if(!q)return;" +
                        "if(typeof q.x==='number'&&isFinite(q.x)){if(q.x<0)q.x=0;else if(q.x>bd.W)q.x=bd.W;}" +
                        "if(typeof q.fy==='number'&&isFinite(q.fy)){var hi=bd.floorY-300;if(q.fy<hi)q.fy=hi;}" +
                        "}" +
                        "try{" +
                        "var _r=ctl.render;" +
                        "ctl.render=function(){" +
                        "var q=ctl.pet,bd=ctl.bounds;" +
                        "if(q&&q.mode==='drag')unfix(q,bd);" +
                        "return _r.apply(ctl,arguments);" +
                        "};" +
                        "}catch(e){console.error('[freeDrag] '+e.message);}" +
                        "}" +
                        "function applyPrefs(p) {")
                val d = c2.replaceFirst(
                    "function applyPrefs(p) {",
                    "function applyPrefs(p) { window.__dshPet = window.__dshPet || {}; " +
                        "window.__dshPet.ctl = ctl; window.__dshPet.vp = innerWidth; " +
                        "window.__dshPet.inner = innerWidth; __dshLookGuard(); __dshFreeDrag();")
                // 拖拽的跟随速度：上游 ease(28,dt) 每帧只补 37% 的差距，手感偏"拖泥带水"。
                // 提到 75（每帧约 70%）跟手得多，又不会像 1:1 那样抖。
                val d1 = d.replaceFirst(
                    "pet.dx = lerp(pet.dx, pointer.x, ease(28, dt));",
                    "pet.dx = lerp(pet.dx, pointer.x, ease(75, dt));")
                val d2 = d1.replaceFirst(
                    "pet.dy = lerp(pet.dy, Math.min(pointer.y, floorY - 245 * S), ease(28, dt));",
                    "pet.dy = lerp(pet.dy, Math.min(pointer.y, floorY - 245 * S), ease(75, dt));")
                // 把页面自己的「双击弹输入框」「长按弹菜单」暴露出来：
                // 穿透模式下页面收不到真实事件（dblclick 还被 killJs 吞了），
                // 原生端必须能把这两个动作显式喊一次，两种模式才会表现一致。
                val e = d2.replaceFirst(
                    "function openInput() {",
                    "window.__dshUI = window.__dshUI || {}; window.__dshUI.input = openInput;\n" +
                        "function openInput() {")
                val f = e.replaceFirst(
                    "function openMenu(x, y) {",
                    "window.__dshUI = window.__dshUI || {}; window.__dshUI.menu = openMenu;\n" +
                        "function openMenu(x, y) {")
                if (f != js) {
                    data = f.toByteArray(Charsets.UTF_8)
                    log("pet-app.js 注入: ctl" +
                        (if (f.contains("__dshPet.ctl = ctl")) "✓" else "✗") +
                        " 守卫" + (if (f.contains("__dshLookGuard();")) "✓" else "✗") +
                        " UI" + (if (f.contains("__dshUI.menu = openMenu")) "✓" else "✗"))
                } else {
                    log("pet-app.js 注入: ⚠ 上游结构变了，未注入")
                }
            }
            if (path.endsWith("/pet.html") || path == "/web/pet.html") {
                val html = String(data, Charsets.UTF_8)
                val css = "<style>html,body{background:transparent!important;" +
                        "background-color:transparent!important;background-image:none!important}" +
                        "body.tab{background:transparent!important;background-image:none!important}" +
                        "body.tab .floor{display:none!important}</style>"
                // ★ 兼容性注入：
                //  ① 把网页里的未捕获 JS 异常打到 console（→ 回传到 App 日志，诊断能看到）
                //  ② WebGL2 context 拿不到时逐级降级重试（老 WebView / 弱 GPU 上带 antialias
                //     等参数会直接失败，上游 rig.js 一失败就 throw，表现为"模型整个消失"）
                val glShim = "<script>(function(){" +
                    "window.addEventListener('error',function(e){try{console.error('[jsError] '+(e.message||'')+' @ '+String(e.filename||'').split('/').pop()+':'+(e.lineno||''));}catch(_){}},true);" +
                    "window.addEventListener('unhandledrejection',function(e){try{console.error('[promise] '+((e.reason&&e.reason.message)||e.reason));}catch(_){}});" +
                    "var orig=HTMLCanvasElement.prototype.getContext;" +
                    "var tries=[{antialias:false,depth:true,stencil:true},{antialias:false,depth:false,stencil:false},{antialias:false,depth:false,stencil:false,preserveDrawingBuffer:false,failIfMajorPerformanceCaveat:false}];" +
                    "HTMLCanvasElement.prototype.getContext=function(t,a){" +
                    "if(t!=='webgl2'&&t!=='webgl')return orig.apply(this,arguments);" +
                    "var r=null;try{r=orig.call(this,t,a);}catch(e){console.error('[glShim] '+t+' threw: '+e);}" +
                    "if(r)return r;" +
                    "console.error('[glShim] '+t+' 按原参数失败，开始降级重试');" +
                    "for(var i=0;i<tries.length;i++){var x={};for(var k in tries[i])x[k]=tries[i][k];" +
                    "if(a)for(var k2 in a)if(k2!=='antialias'&&k2!=='depth'&&k2!=='stencil')x[k2]=a[k2];" +
                    "try{var rr=orig.call(this,t,x);if(rr){console.error('[glShim] '+t+' 降级成功('+i+')');return rr;}}catch(e2){}}" +
                    "console.error('[glShim] '+t+' 完全不可用');return null;};})()</script>"
                val hostJs = "<script>window.petHost=window.petHost||{" +
                    "setInteractive:function(){}," +
                    "focus:function(){}," +
                    "grabFocus:function(){}," +
                    "releaseFocus:function(){}," +
                    "hide:function(){}," +
                    "onCursor:function(){}," +
                    "followCursor:function(){return Promise.resolve(null);}," +
                    "sampleBackdrop:function(){return new Array(300).fill(255);}" +
                    "};</script>"
                val noHalo = "<style>#pet,#pet *{filter:none !important;}</style>"
                val killJs = "<script>window.addEventListener(\"dblclick\",function(e){" +
                    "e.stopPropagation();e.preventDefault();},true);</script>"
                // 网页侧握手：把 pet-app.js 里的 ctl 拿到手，并暴露"抓/拖/放"。
                // 原生交互层（跟手那一小块可触摸窗口）靠它把她拎起来，
                // 走的是她自己的拖拽动画，不是搬窗口那种平移。
                val petJs = "<script>" + "(function(){" +
                    "var p=window.__dshPet=window.__dshPet||{};" +
                    "p.roam='free';" +
                    "p.ctl=null;" +
                    "p.can=function(){return !!(p.ctl&&p.ctl.pet);};" +
                    // 原生传进来的都是"相对交互层窗口左上角的 CSS 像素"，直接用
                    "function cv(lx,ly){return {x:lx,y:ly};}" +
                    // 按下的那一点换算成舞台坐标，并算她"身体该在哪"——锚点就是手指那一点。
                    // 注意：不能拿 pet.y 去迭代（上游那个字段是 NaN，真正的纵向位置是 pet.fy）。
                    // 手指那一点若没落在她身上，就朝她的中心找最近能命中的点；
                    // 都不行就退到她的中心 —— **绝不能因为"差几个像素"就让这一次触摸石沉大海**。
                    // （上游 pointerDown 里有 `if(!hitPet(p)) return false`，喂一个没命中的点等于什么都没发生。）
                    "p.grab=function(lx,ly){if(!p.can())return false;" +
                    "var a=cv(lx,ly);" +
                    "var s=p.ctl.toStage(a.x,a.y);" +
                    "var cx=a.x,cy=a.y,i=0,g=false;" +
                    "for(;i<96;i++){if(p.ctl.hitPet({x:cx,y:cy})){g=true;break;}" +
                    "cx=a.x+(s.x-a.x)*(1-i/96);cy=a.y+(s.y-a.y)*(1-i/96);}" +
                    "if(!g){cx=s.x;cy=s.y;}" +
                    // 指针位置由**我**独立累加，起始值取命中点（不是她的位置）。
                    // 关键：绝不要用 pet.dx/pet.x 去构造它 —— 那等于自引用，
                    // 而她的拖拽是 pet.dx = lerp(pet.dx, pointer.x, ease(28,dt))，
                    // 自引用会让每帧补一大截差距，表现就是"一拖就飞"。
                    "p.pt={x:cx,y:cy};p.lx=a.x;p.ly=a.y;" +
                    "p.ctl.pointerDown({x:cx,y:cy});" +
                    "p.ctl.pointerMove({x:cx+8,y:cy+8});" +
                    "return true;};" +
                    // 拖动：指针位置 += 手指增量（绝对坐标独立累加）
                    "p.grabMove=function(lx,ly){if(!p.can())return false;" +
                    "var a=cv(lx,ly);" +
                    "if(!p.pt){p.grab(a.x,a.y);return true;}" +
                    "var dx=a.x-p.lx,dy=a.y-p.ly;" +
                    "p.lx=a.x;p.ly=a.y;" +
                    "if(dx||dy){p.pt.x+=dx;p.pt.y+=dy;p.ctl.pointerMove({x:p.pt.x,y:p.pt.y});}" +
                    "return true;};" +
                    "p.grabEnd=function(lx,ly){if(!p.can())return false;" +
                    "var a=cv(lx,ly);" +
                    "p.ctl.pointerUp();" +
                    "p.pt=null;return true;};" +
                    // 显式喊页面自己的两个动作：穿透模式下页面收不到真实事件，
                    // 而 dblclick 还被 killJs 吞了 —— 只能这样，两种模式才一致。
                    "p.dbl=function(lx,ly){try{var a=cv(lx,ly);if(window.__dshUI&&window.__dshUI.input){window.__dshUI.input();return true;}}catch(e){}return false;};" +
                    "p.menu=function(lx,ly){try{var a=cv(lx,ly);if(window.__dshUI&&window.__dshUI.menu){window.__dshUI.menu(a.x,a.y);return true;}}catch(e){}return false;};" +
                    "p.cancel=function(){if(p.can())try{p.ctl.pointerUp();}catch(e){}p.pt=null;};" +
                    "})()</script>"
                val posJs = killJs + petJs + "<script>" + "(function(){var last=0,lx=-1,ly=-1,lw=-1,lh=-1;" +
                    "function tick(ts){" +
                    "var e=document.getElementById('pet');" +
                    "if(!e||!window.AndroidPet||!window.AndroidPet.pos){window.requestAnimationFrame(tick);return;}" +
                    "if(ts-last>60||window.__dshPet&&window.__dshPet.pt){last=ts;" +
                    "var r=e.getBoundingClientRect();" +
                    "var x=Math.round(r.left),y=Math.round(r.top),w=Math.round(r.width),h=Math.round(r.height);" +
                    // ★ 她实际画出来的范围＝交互层必须盖住的范围。用画布 alpha 的真实包围盒来定位，
                    //   否则窗口会按 #pet 的包围盒偏到别处（实测：触点在正中/偏上才有反应）。
                    "try{var ab=alphaBounds();if(ab&&ab[2]>2&&ab[3]>2){x=Math.round(ab[0]);y=Math.round(ab[1]);w=Math.round(ab[2]);h=Math.round(ab[3]);}}catch(err){}" +
                    "if(Math.abs(x-lx)>2||Math.abs(y-ly)>2||w!==lw||h!==lh){lx=x;ly=y;lw=w;lh=h;" +
                    "if(window.AndroidPet&&window.AndroidPet.pos){" +
                    "try{if(window.AndroidPet.vp)window.AndroidPet.vp(window.innerWidth);}catch(err){}" +
                    "try{window.AndroidPet.pos(x,y,w,h);}catch(err){}}}}" +
                    "window.requestAnimationFrame(tick);}" +
                    "window.requestAnimationFrame(tick);" +
                    // alpha 包围盒：缓存 260ms（她一直在动，缓存太久窗口就追不上 → 你按下去时她已走开）
                    "var abAt=0,abCache=null;" +
                    "function alphaBounds(){var now=performance.now();if(abCache&&now-abAt<260)return abCache;" +
                    "var s=document.querySelector('#pet canvas');if(!s||!s.width)return null;" +
                    "var o=document.createElement('canvas');o.width=s.width;o.height=s.height;" +
                    "var c=o.getContext('2d');c.clearRect(0,0,o.width,o.height);c.drawImage(s,0,0);" +
                    "var d=c.getImageData(0,0,o.width,o.height).data,W=o.width,H=o.height;" +
                    "var x0=W,y0=H,x1=-1,y1=-1;" +
                    "for(var y=0;y<H;y++){var row=y*W;for(var x=0;x<W;x++){if(d[(row+x)*4+3]>24){" +
                    "if(x<x0)x0=x;if(x>x1)x1=x;if(y<y0)y0=y;if(y>y1)y1=y;}}}" +
                    "if(x1<x0||y1<y0){abCache=null;abAt=now;return null;}" +
                    "var sr=s.getBoundingClientRect(),k=sr.width/W;" +
                    // 注意：这里**不要**去加 (pet.dx-pet.x) 之类的"视觉偏移"——
                    // pet.dx/dy 与 pet.x/fy 不在同一套坐标里（实测相减会得到 -833 这种量，
                    // 直接把窗口推出屏幕，并形成正反馈让她越拖越飞）。
                    "abCache=[sr.left+x0*k,sr.top+y0*k,(x1-x0+1)*k,(y1-y0+1)*k];abAt=now;return abCache;}" +
                    "})()</script>"
                val noHover = "<style>#tools{display:none !important;}</style>"
                // 气泡样式：默认只用原生气泡（灰），把网页那个白气泡藏掉，避免两个重叠
                val bubbleCss = if (prefs.getString("bubble_mode", "page") == "native")
                    "<style>#bubble{display:none !important;}</style>" else ""

                val patched = if (html.contains("</head>")) html.replaceFirst("</head>", css + noHalo + noHover + bubbleCss + glShim + hostJs + posJs + "</head>")
                              else css + noHalo + noHover + bubbleCss + hostJs + posJs + html
                data = patched.toByteArray(Charsets.UTF_8)
            }
            val head = "HTTP/1.1 200 OK\r\nContent-Type: ${mimeOf(path)}\r\nContent-Length: ${data.size}\r\n" +
                    "Cache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(data)
            out.flush()
        } catch (e: Exception) {
            reply(out, 404, "text/plain; charset=utf-8", "404 $path")
        }
    }

    /* ---------------- 极简 WebSocket ---------------- */

    private fun websocket(sock: Socket, out: java.io.OutputStream, key: String?, query: String) {
        if (key == null) return
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest(
                (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)))
        val handshake = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n" +
                "Connection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n"
        out.write(handshake.toByteArray(Charsets.ISO_8859_1))
        out.flush()

        // 桌宠连上就收到 init，里面带上已保存的肤色
        if (query.contains("role=pet")) {
            log("准备给桌宠发 init")
            try { sock.soTimeout = 0 } catch (_: Exception) {}
            petOut = out
            petSock = sock
            log("桌宠 socket 连上了 ($query)")
            val skin = prefs.getString("skin", null)
            val theme = prefs.getString("prefs", null)
            val sb = StringBuilder("{\"t\":\"init\",\"scale\":1,\"roam\":\"free\",\"sound\":true")
            sb.append(",\"bot\":{\"name\":\"大肥鱼\",\"controls\":true,\"paused\":false,\"quitLabel\":\"退出桌宠\",")
            sb.append("\"buttons\":{\"dress\":true,\"pause\":false,\"settings\":false,\"quit\":true}}")
            if (theme != null && theme.contains("dark")) sb.append(",\"theme\":\"dark\"")
            if (skin != null && skin.length > 2) {
                // 装扮页 POST 的是 {"skin":{...}}，这里取内层对象
                val inner = extractSkin(skin)
                if (inner != null) sb.append(",\"skin\":").append(inner)
            }
            sb.append("}")
            sendText(out, sb.toString())
        }

        // 之后保持连接、回应 ping/close（桌宠会一直连着）
        val input = sock.getInputStream()
        try {
            while (true) {
                val b0 = input.read()
                if (b0 < 0) break
                val opcode = b0 and 0x0f
                val lenByte = input.read()
                if (lenByte < 0) break
                var len = lenByte and 0x7f
                if (len == 126) {
                    len = (input.read() shl 8) or input.read()
                } else if (len == 127) {
                    var l = 0L
                    for (i in 0 until 8) l = (l shl 8) or input.read().toLong()
                    len = l.toInt()
                }
                val masked = (lenByte and 0x80) != 0
                val mask = ByteArray(4)
                if (masked) {
                    var r = 0
                    while (r < 4) {
                        val n = input.read(mask, r, 4 - r)
                        if (n <= 0) break
                        r += n
                    }
                }
                val payload = ByteArray(len)
                var got = 0
                while (got < len) {
                    val n = input.read(payload, got, len - got)
                    if (n <= 0) break
                    got += n
                }
                // 浏览器发来的帧是带掩码的，必须还原，否则全是乱码
                if (masked) {
                    for (i in 0 until len) {
                        payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                    }
                }
                when (opcode) {
                    0x1 -> handleIncoming(String(payload, Charsets.UTF_8))
                    0x8 -> { sendFrame(out, 0x8, ByteArray(0)); break }
                    0x9 -> sendFrame(out, 0xA, payload)
                }
            }
        } catch (_: Exception) {
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private fun extractSkin(raw: String): String? {
        val i = raw.indexOf("\"skin\"")
        if (i < 0) return null
        val start = raw.indexOf('{', i)
        if (start < 0) return null
        var depth = 0
        for (j in start until raw.length) {
            when (raw[j]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return raw.substring(start, j + 1) }
            }
        }
        return null
    }

    private fun sendText(out: java.io.OutputStream, text: String) {
        sendFrame(out, 0x1, text.toByteArray(Charsets.UTF_8))
    }

    private fun sendFrame(out: java.io.OutputStream, opcode: Int, payload: ByteArray) {
        val head = ArrayList<Byte>()
        head.add((0x80 or opcode).toByte())
        when {
            payload.size < 126 -> head.add(payload.size.toByte())
            payload.size < 65536 -> {
                head.add(126.toByte())
                head.add(((payload.size shr 8) and 0xff).toByte())
                head.add((payload.size and 0xff).toByte())
            }
            else -> {
                head.add(127.toByte())
                for (i in 7 downTo 0) head.add(((payload.size.toLong() shr (8 * i)) and 0xff).toByte())
            }
        }
        out.write(head.toByteArray())
        out.write(payload)
        out.flush()
    }

    private fun mimeOf(path: String): String = when {
        path.endsWith(".html") -> "text/html; charset=utf-8"
        path.endsWith(".js") || path.endsWith(".mjs") -> "text/javascript; charset=utf-8"
        path.endsWith(".css") -> "text/css; charset=utf-8"
        path.endsWith(".json") -> "application/json; charset=utf-8"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
        path.endsWith(".webp") -> "image/webp"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".wav") -> "audio/wav"
        path.endsWith(".mp3") -> "audio/mpeg"
        path.endsWith(".woff2") -> "font/woff2"
        else -> "application/octet-stream"
    }
}
