package com.mrgreenapps.a11ypilot.agent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Tap-to-toggle wrapper around Android's [SpeechRecognizer]. The recognizer must be created and
 * destroyed on the main thread.
 */
class SpeechInput(private val appContext: Context) {

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var recognizer: SpeechRecognizer? = null
    @Volatile var listening: Boolean = false; private set

    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
        fun onEnd()
    }

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    fun start(listener: Listener) {
        main.post {
            if (listening) return@post
            if (!isAvailable()) {
                listener.onError("当前设备不支持语音识别")
                return@post
            }
            val r = SpeechRecognizer.createSpeechRecognizer(appContext)
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "录音出错"
                        SpeechRecognizer.ERROR_CLIENT -> "语音识别客户端错误"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限"
                        SpeechRecognizer.ERROR_NETWORK -> "网络错误，语音识别需要联网"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                        SpeechRecognizer.ERROR_NO_MATCH -> "没有识别到语音"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别忙，请稍后再试"
                        SpeechRecognizer.ERROR_SERVER -> "语音识别服务错误"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有检测到语音"
                        else -> "语音识别错误 $error"
                    }
                    listener.onError(msg)
                    cleanup()
                    listener.onEnd()
                }
                override fun onResults(results: Bundle?) {
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = list?.firstOrNull().orEmpty()
                    if (text.isNotEmpty()) listener.onFinal(text)
                    cleanup()
                    listener.onEnd()
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = list?.firstOrNull().orEmpty()
                    if (text.isNotEmpty()) listener.onPartial(text)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            try {
                listening = true
                r.startListening(intent)
            } catch (t: Throwable) {
                listener.onError(t.message ?: "startListening failed")
                cleanup()
                listener.onEnd()
            }
        }
    }

    fun stop() {
        main.post {
            try { recognizer?.stopListening() } catch (_: Throwable) {}
        }
    }

    fun cancel() {
        main.post {
            try { recognizer?.cancel() } catch (_: Throwable) {}
            cleanup()
        }
    }

    private fun cleanup() {
        listening = false
        try { recognizer?.destroy() } catch (_: Throwable) {}
        recognizer = null
    }
}
