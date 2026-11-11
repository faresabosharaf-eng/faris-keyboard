package com.faris.pckeyboard

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

object VoiceBridge {
    @Volatile var pending: String? = null
    @Volatile var time: Long = 0L
}

class VoiceActivity : Activity() {
    private var recognizer: SpeechRecognizer? = null
    private var arabic = false
    private var started = false
    private lateinit var label: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arabic = intent.getBooleanExtra("arabic", false)
        val d = resources.displayMetrics.density
        val pad = (24 * d).toInt()
        label = TextView(this)
        label.text = "🎤  تكلّم الآن..."
        label.textSize = 20f
        label.setTextColor(Color.WHITE)
        label.gravity = Gravity.CENTER
        label.setPadding(pad, pad, pad, pad)
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#1F5FD6"))
        bg.cornerRadius = 24f * d
        label.background = bg

        val frame = FrameLayout(this)
        frame.addView(
            label,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        frame.setOnClickListener { finish() }
        setContentView(frame)

        if (Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            Toast.makeText(this, "لازم تسمح للميكروفون عشان الكتابة بالصوت", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun startListening() {
        if (started) return
        started = true
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "خدمة التعرف على الصوت غير متوفرة في جوالك", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { label.text = "..." }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) {
                val msg = if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                    "ما سمعت شي، جرّب مرة ثانية" else "تعذر التعرف على الصوت (رمز $error)"
                Toast.makeText(this@VoiceActivity, msg, Toast.LENGTH_SHORT).show()
                finish()
            }
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val t = list?.firstOrNull()
                if (!t.isNullOrBlank()) {
                    VoiceBridge.pending = t
                    VoiceBridge.time = System.currentTimeMillis()
                }
                finish()
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (arabic) "ar-SA" else "en-US")
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        r.startListening(i)
    }

    override fun onDestroy() {
        recognizer?.destroy()
        super.onDestroy()
    }
}
