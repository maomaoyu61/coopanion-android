package com.maomaoyu.coopanionpet

import android.content.Context
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 极简本地 HTTP 服务：把 APK 内 assets/web/ 下的桌宠网页端出来。
 * 之所以不用 file://，是因为上游的 pet.html 用的是 /web/ 绝对路径，
 * 需要按它原本的 URL 结构提供，行为才和电脑版一致。
 */
class AssetServer(private val ctx: Context) {

    private var serverSocket: ServerSocket? = null
    var port: Int = 0
        private set

    fun start() {
        val ss = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
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

    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private fun handle(sock: Socket) {
        try {
            sock.use { s ->
                val out = s.getOutputStream()
                val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine = reader.readLine() ?: return
                var line: String?
                do { line = reader.readLine() } while (!line.isNullOrEmpty())

                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                var path = parts[1].substringBefore('?')
                if (path == "/" || path.isEmpty()) path = "/web/pet.html"

                val assetPath = path.trimStart('/')
                val mime = mimeOf(path)

                try {
                    val stream: InputStream = ctx.assets.open(assetPath)
                    val data = stream.use { it.readBytes() }
                    val head = "HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${data.size}\r\n" +
                            "Cache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
                    out.write(head.toByteArray(Charsets.ISO_8859_1))
                    out.write(data)
                    out.flush()
                } catch (e: Exception) {
                    val body = "404 $path".toByteArray()
                    val head = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    out.write(head.toByteArray(Charsets.ISO_8859_1))
                    out.write(body)
                    out.flush()
                }
            }
        } catch (_: Exception) {
        }
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
