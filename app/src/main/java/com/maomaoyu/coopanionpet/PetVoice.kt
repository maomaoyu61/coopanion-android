package com.maomaoyu.coopanionpet

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 语音口令（参考"小爱同学"那种）：一直听着，听到「大肥鱼…」就执行。
 *
 * - **默认关闭**，必须由用户在 App 里显式打开
 * - 只在本机用系统语音识别；识别结果只在本地用，不上传
 * - 每次识别结束会自动重听；息屏或服务停止时暂停
 */
class PetVoice(private val ctx: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private var running = false

    fun isRunning() = running

    private fun log(s: String) { PetService.instance?.server?.log("语音口令: " + s) }

    fun start() {
        if (running) return
        // 先自查：有没有识别服务、有没有录音权限 —— 不然用户只会看到"没反应"
        try {
            val avail = SpeechRecognizer.isRecognitionAvailable(ctx)
            val mic = ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            log("启动自查 → 识别服务=" + (if (avail) "有" else "没有 ✗") + "，录音权限=" + (if (mic) "有" else "没有 ✗"))
            if (!avail) {
                PetService.instance?.say("这台手机没有语音识别服务，我开不了免手模式（试试用输入法的麦克风打字给我）")
                running = false
                return
            }
            if (!mic) {
                PetService.instance?.say("还没有麦克风权限，去 App 里给一下")
                running = false
                return
            }
        } catch (e: Exception) {
            log("自查出错: " + e.javaClass.simpleName)
        }
        running = true
        log("开始监听（口令词：大肥鱼）")
        listenOnce()
    }

    fun stop() {
        running = false
        try { sr?.destroy() } catch (_: Exception) {}
        sr = null
    }

    private fun listenOnce() {
        if (!running) return
        try {
            sr?.destroy()
        } catch (_: Exception) {}
        try {
            sr = SpeechRecognizer.createSpeechRecognizer(ctx)
            sr?.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = list?.firstOrNull()?.trim().orEmpty()
                    log("听到：" + text)
                    if (text.isNotEmpty()) {
                        val cmd = PetCmd.stripWake(text)
                        if (cmd != null) {
                            // 带了口令词才执行，避免把旁边聊天当指令
                            val reply = try { PetCmd.handle("大肥鱼 " + cmd) } catch (e: Exception) { null }
                            if (reply != null) PetService.instance?.say(reply)
                        }
                    }
                    if (running) handler.postDelayed({ listenOnce() }, 400)
                }

                override fun onError(error: Int) {
                    log("识别出错 code=" + error)
                    if (running) handler.postDelayed({ listenOnce() }, 1500)
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { log("说完了一句") }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            sr?.startListening(i)
        } catch (_: Exception) {
            if (running) handler.postDelayed({ listenOnce() }, 2000)
        }
    }
}
