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

    fun start() {
        if (running) return
        running = true
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
                    if (running) handler.postDelayed({ listenOnce() }, 1500)
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
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
