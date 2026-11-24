package com.houxinzhe.pib.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.util.Locale

data class SpeechHandle(val recording: Boolean, val toggle: () -> Unit, val start: () -> Unit, val stop: () -> Unit)

/**
 * In-app speech recognition (supports hold-to-talk and tap-to-toggle, matching iOS),
 * with automatic fallback to system RecognizerIntent when inline SpeechRecognizer is absent.
 */
@Composable
fun rememberSpeechInput(onText: (String) -> Unit): SpeechHandle {
    val ctx = LocalContext.current
    var recording by remember { mutableStateOf(false) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    val fallbackLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        recording = false
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onText)
    }

    fun launchFallback() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话")
        if (intent.resolveActivity(ctx.packageManager) != null) fallbackLauncher.launch(intent)
        else Toast.makeText(ctx, "这台手机没有可用的语音识别服务", Toast.LENGTH_SHORT).show()
    }

    fun beginInline() {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            launchFallback(); return
        }
        val sr = recognizer ?: SpeechRecognizer.createSpeechRecognizer(ctx).also { created ->
            created.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { recording = true }
                override fun onBeginningOfSpeech() { recording = true }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { recording = false }
                override fun onError(error: Int) {
                    val wasRec = recording
                    recording = false
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS || error == SpeechRecognizer.ERROR_CLIENT) {
                        if (wasRec) launchFallback()
                    }
                }
                override fun onResults(results: Bundle?) {
                    recording = false
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onText)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            recognizer = created
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        recording = true
        runCatching { sr.startListening(intent) }.onFailure { recording = false; launchFallback() }
    }

    val micPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginInline() else Toast.makeText(ctx, "需要麦克风权限才能语音输入", Toast.LENGTH_SHORT).show()
    }

    fun start() {
        if (recording) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginInline()
        else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun stop() {
        if (!recording) return
        recording = false
        runCatching { recognizer?.stopListening() }
    }

    DisposableEffect(Unit) {
        onDispose { runCatching { recognizer?.destroy() } }
    }

    return SpeechHandle(recording = recording, toggle = { if (recording) stop() else start() }, start = ::start, stop = ::stop)
}

/** Optional system Mandarin TTS speaker for the original Pib character (zero voice-cloning IP risk). */
class PibSpeaker(context: Context) {
    private var ready = false
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            val res = tts?.setLanguage(Locale.CHINA)
            ready = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
            tts?.setSpeechRate(1.04f)
        }
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        val clean = text.replace(Regex("[#*`_~>\\[\\]]"), "").trim().take(400)
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "pib_utt")
    }

    fun stop() {
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
    }
}
