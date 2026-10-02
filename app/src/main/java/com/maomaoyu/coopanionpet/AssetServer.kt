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
    private var walkSeq = 0
    private var walkSide = false
    private var saySeq = 0
    private val logs = ArrayDeque<String>()

    /** 记一条日志（同时供 /log 接口读取，方便在电脑/容器里排错）。 */
    fun log(line: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        synchronized(logs) {
            logs.addLast("$ts $line")
            while (logs.size > 300) logs.removeFirst()
        }
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
        val out = petOut ?: return
        saySeq++
        val beat = JSONObject().apply {
            put("text", text)
            put("actions", JSONArray(actions))
            put("anchors", JSONArray())
        }
        val msg = JSONObject().apply {
            put("t", "say")
            put("id", "s$saySeq")
            put("beats", JSONArray().put(beat))
        }
        rawToPet(out, msg.toString())
    }

    /** 提问（own=true 时气泡里会出现输入框，用户可以打字）。 */
    fun sendAsk(question: String, options: List<String>, own: Boolean) {
        val out = petOut ?: return
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
        val out = petOut ?: return
        val msg = JSONObject().apply { put("t", "thinking"); put("on", on) }
        rawToPet(out, msg.toString())
    }

    private fun rawToPet(out: java.io.OutputStream, json: String) {
        try {
            synchronized(out) { sendText(out, json) }
        } catch (_: Exception) {
            petOut = null
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
        val out = petOut ?: return
        walkSeq++
        walkSide = !walkSide
        val to = if (walkSide) 0.12 else 0.88
        val json = "{\"t\":\"walk\",\"id\":\"w$walkSeq\",\"to\":$to,\"run\":$run}"
        try {
            synchronized(out) { sendText(out, json) }
        } catch (_: Exception) {
            petOut = null
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
            if (path.endsWith("/pet.html") || path == "/web/pet.html") {
                val html = String(data, Charsets.UTF_8)
                val css = "<style>html,body{background:transparent!important;" +
                        "background-color:transparent!important;background-image:none!important}" +
                        "body.tab{background:transparent!important;background-image:none!important}" +
                        "body.tab .floor{display:none!important}</style>"
                val hostJs = "<script>window.petHost=window.petHost||{" +
                    "setInteractive:function(){}," +
                    "focus:function(){}," +
                    "grabFocus:function(){}," +
                    "releaseFocus:function(){}," +
                    "hide:function(){}," +
                    "onCursor:function(){}," +
                    "followCursor:function(){return Promise.resolve(null);}" +
                    "};</script>"
                val patched = if (html.contains("</head>")) html.replaceFirst("</head>", css + hostJs + "</head>")
                              else css + hostJs + html
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
            petOut = out
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
