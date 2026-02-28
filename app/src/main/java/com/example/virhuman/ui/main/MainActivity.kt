package com.example.virhuman.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.virhuman.R
import com.example.virhuman.ai.asr.AsrEngine
import com.example.virhuman.ai.asr.SherpaAsrEngine
import com.example.virhuman.ai.tts.SherpaTtsEngine
import com.example.virhuman.ai.tts.TtsEngine
import com.example.virhuman.databinding.ActivityMainBinding
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), AsrEngine.Callback {
    private lateinit var binding: ActivityMainBinding
    private var asrEngine: AsrEngine? = null
    private var ttsEngine: TtsEngine? = null
    private var isListening = false
    @Volatile private var asrInitializing = false
    @Volatile private var ttsInitializing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        warmupEngines()

        binding.btnStartListen.setOnClickListener {
            if (isListening) {
                asrEngine?.stopListening()
                isListening = false
                binding.btnStartListen.setText(R.string.start_asr)
                return@setOnClickListener
            }
            ensureAudioPermission {
                ensureAsrReady { engine ->
                    if (engine == null || !engine.isAvailable()) {
                        binding.tvSubtitle.setText(R.string.asr_not_available)
                        return@ensureAsrReady
                    }
                    binding.tvSubtitle.text = ""
                    engine.startListening()
                    isListening = true
                    binding.btnStartListen.setText(R.string.stop_asr)
                }
            }
        }

        binding.btnTtsTest.setOnClickListener {
            ensureTtsReady { engine ->
                if (engine == null || !engine.isReady()) {
                    binding.tvSubtitle.setText(R.string.tts_not_ready)
                    return@ensureTtsReady
                }
                val success = engine.speak(getString(R.string.tts_demo_text))
                if (!success) {
                    binding.tvSubtitle.setText(R.string.tts_speak_failed)
                }
            }
        }
    }

    private fun ensureAudioPermission(onGranted: () -> Unit) {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            onGranted()
            return
        }
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
    }

    override fun onPartialResult(text: String) {
        runOnUiThread {
            binding.tvSubtitle.text = text
        }
    }

    override fun onFinalResult(text: String) {
        runOnUiThread {
            binding.tvSubtitle.text = text
            isListening = false
            binding.btnStartListen.setText(R.string.start_asr)
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            binding.tvSubtitle.text = getString(R.string.asr_error, message)
            isListening = false
            binding.btnStartListen.setText(R.string.start_asr)
        }
    }

    override fun onStopped() {
        runOnUiThread {
            isListening = false
            binding.btnStartListen.setText(R.string.start_asr)
            Toast.makeText(this, R.string.asr_stopped_toast, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        asrEngine?.release()
        asrEngine = null
        ttsEngine?.release()
        ttsEngine = null
    }

    private fun ensureAsrReady(showHint: Boolean = true, onReady: (AsrEngine?) -> Unit) {
        val existing = asrEngine
        if (existing != null) {
            onReady(existing)
            return
        }
        if (asrInitializing) return
        asrInitializing = true
        if (showHint) binding.tvSubtitle.setText(R.string.asr_initializing)
        thread(start = true, name = "asr-init") {
            val engine = try {
                SherpaAsrEngine(this, this)
            } catch (_: Throwable) {
                null
            }
            asrEngine = engine
            asrInitializing = false
            runOnUiThread { onReady(engine) }
        }
    }

    private fun ensureTtsReady(showHint: Boolean = true, onReady: (TtsEngine?) -> Unit) {
        val existing = ttsEngine
        if (existing != null) {
            onReady(existing)
            return
        }
        if (ttsInitializing) return
        ttsInitializing = true
        if (showHint) binding.tvSubtitle.setText(R.string.tts_initializing)
        thread(start = true, name = "tts-init") {
            val engine = try {
                SherpaTtsEngine(this)
            } catch (_: Throwable) {
                null
            }
            ttsEngine = engine
            ttsInitializing = false
            runOnUiThread { onReady(engine) }
        }
    }

    private fun warmupEngines() {
        // Preload in background to reduce first-click latency.
        ensureAsrReady(showHint = false) {}
        ensureTtsReady(showHint = false) {}
    }
}
