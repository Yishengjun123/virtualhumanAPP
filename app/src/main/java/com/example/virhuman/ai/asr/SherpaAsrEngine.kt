package com.example.virhuman.ai.asr

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.sqrt
import java.util.concurrent.atomic.AtomicBoolean

class SherpaAsrEngine(
    private val context: Context,
    initialCallback: AsrEngine.Callback
) : AsrEngine {
    @Volatile
    private var callback: AsrEngine.Callback = initialCallback
    private val sampleRate = 16000
    private val recording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val audioCache = ArrayList<Float>(sampleRate * 12)

    private var recognizer: OfflineRecognizer? = null
    private var ready = false

    // Partial decoding + silence auto-stop
    private val partialDecodeIntervalMs = 700L
    private val minSamplesForPartial = sampleRate / 2
    private val silenceTimeoutMs = 2000L
    private val minSpeechRms = 0.010f
    private val speechStartRequiredMs = 220L
    private val noSpeechTimeoutMs = 6500L
    private val maxUtteranceMs = 20000L
    private val stableTextTimeoutMs = 2800L
    private val minUtteranceForStableStopMs = 1200L
    private var lastDecodeTimeMs = 0L
    private var lastSpeechTimeMs = 0L
    private var utteranceStartMs = 0L
    private var listeningStartMs = 0L
    private var hasSpeech = false
    private var lastPartialText = ""
    private var noiseRms = 0.003f
    private var speechAccumMs = 0L
    private var silenceAccumMs = 0L
    private var lastTextChangeMs = 0L
    private val stoppedNotified = AtomicBoolean(false)

    init {
        try {
            val senseVoice = OfflineSenseVoiceModelConfig().apply {
                model = "sherpa/stt/model.int8.onnx"
                language = "auto"
                useInverseTextNormalization = true
            }

            val modelConfig = OfflineModelConfig().apply {
                this.senseVoice = senseVoice
                tokens = "sherpa/stt/tokens.txt"
                numThreads = 2
                debug = false
                provider = "cpu"
                modelType = "sense_voice"
            }

            val config = OfflineRecognizerConfig().apply {
                this.modelConfig = modelConfig
                decodingMethod = "greedy_search"
            }

            recognizer = OfflineRecognizer(context.assets, config)
            ready = recognizer != null
        } catch (e: Exception) {
            ready = false
            callback.onError("Init sherpa STT failed: ${e.message}")
        }
    }

    override fun isAvailable(): Boolean = ready

    fun updateCallback(newCallback: AsrEngine.Callback) {
        callback = newCallback
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun startListening() {
        if (!ready) {
            callback.onError("Sherpa STT is not ready")
            return
        }
        if (recording.get()) return

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = (minBuffer * 2).coerceAtLeast(4096)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            callback.onError("AudioRecord init failed")
            return
        }

        synchronized(audioCache) { audioCache.clear() }
        hasSpeech = false
        listeningStartMs = System.currentTimeMillis()
        lastSpeechTimeMs = System.currentTimeMillis()
        utteranceStartMs = 0L
        lastDecodeTimeMs = 0L
        lastPartialText = ""
        noiseRms = 0.003f
        speechAccumMs = 0L
        silenceAccumMs = 0L
        lastTextChangeMs = System.currentTimeMillis()
        stoppedNotified.set(false)

        recording.set(true)
        audioRecord?.startRecording()
        recordingThread = thread(start = true, name = "sherpa-sensevoice-recording") {
            val shortBuffer = ShortArray(1024)

            while (recording.get()) {
                val n = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: 0
                if (n <= 0) continue

                val chunk = FloatArray(n)
                var sumSquares = 0.0
                for (i in 0 until n) {
                    val sample = shortBuffer[i] / 32768.0f
                    chunk[i] = sample
                    sumSquares += sample * sample
                }

                synchronized(audioCache) {
                    for (s in chunk) audioCache.add(s)
                }

                val rms = sqrt((sumSquares / n).toFloat())
                val now = System.currentTimeMillis()
                val frameMs = (n * 1000L / sampleRate).coerceAtLeast(1L)

                // Adaptive threshold for different ambient-noise environments.
                val dynamicThreshold = max(minSpeechRms, (noiseRms * 2.2f + 0.0015f).coerceAtMost(0.03f))

                if (!hasSpeech) {
                    // Update noise baseline before speech starts.
                    noiseRms = 0.95f * noiseRms + 0.05f * rms

                    if (rms >= dynamicThreshold) {
                        speechAccumMs += frameMs
                        if (speechAccumMs >= speechStartRequiredMs) {
                            hasSpeech = true
                            lastSpeechTimeMs = now
                            utteranceStartMs = now
                            silenceAccumMs = 0L
                            lastTextChangeMs = now
                        }
                    } else {
                        speechAccumMs = 0L
                    }
                } else {
                    // In speech: allow brief level dips (hangover) to avoid cutting off sentence middle.
                    if (rms >= dynamicThreshold * 0.8f) {
                        lastSpeechTimeMs = now
                        silenceAccumMs = 0L
                    } else {
                        silenceAccumMs += frameMs
                    }
                }

                if (now - lastDecodeTimeMs >= partialDecodeIntervalMs) {
                    val snapshot = synchronized(audioCache) { audioCache.toFloatArray() }
                    if (snapshot.size >= minSamplesForPartial) {
                        val text = decode(snapshot)
                        if (text.isNotBlank() && text != lastPartialText) {
                            lastPartialText = text
                            lastTextChangeMs = now
                            callback.onPartialResult(text)
                            if (!hasSpeech) {
                                hasSpeech = true
                                utteranceStartMs = now
                                silenceAccumMs = 0L
                            }
                        }
                    }
                    lastDecodeTimeMs = now
                }

                val stableTextTimeoutReached =
                    hasSpeech &&
                        lastPartialText.isNotBlank() &&
                        now - utteranceStartMs >= minUtteranceForStableStopMs &&
                        now - lastTextChangeMs >= stableTextTimeoutMs

                if (hasSpeech && (silenceAccumMs >= silenceTimeoutMs || stableTextTimeoutReached || now - utteranceStartMs >= maxUtteranceMs)) {
                    recording.set(false)
                    notifyStoppedOnce()
                }

                if (!hasSpeech && now - listeningStartMs >= noSpeechTimeoutMs) {
                    recording.set(false)
                    notifyStoppedOnce()
                }
            }

            try {
                audioRecord?.stop()
            } catch (_: Exception) {
            }
            audioRecord?.release()
            audioRecord = null

            val finalSamples = synchronized(audioCache) { audioCache.toFloatArray() }
            if (finalSamples.size < minSamplesForPartial) {
                notifyStoppedOnce()
                callback.onError("No speech captured")
                return@thread
            }

            val finalText = decode(finalSamples)
            notifyStoppedOnce()
            if (finalText.isBlank()) {
                callback.onError("No text recognized")
            } else {
                callback.onFinalResult(finalText)
            }
        }
    }

    override fun stopListening() {
        if (!recording.get()) return
        recording.set(false)
        notifyStoppedOnce()
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        recordingThread?.join(300)
        recordingThread = null
    }

    override fun release() {
        stopListening()
        recognizer?.release()
        recognizer = null
        ready = false
    }

    private fun decode(samples: FloatArray): String {
        val localRecognizer = recognizer ?: return ""
        return try {
            val stream = localRecognizer.createStream()
            stream.acceptWaveform(samples, sampleRate)
            localRecognizer.decode(stream)
            val text = localRecognizer.getResult(stream).text.orEmpty()
            stream.release()
            text
        } catch (_: Exception) {
            ""
        }
    }

    private fun notifyStoppedOnce() {
        if (stoppedNotified.compareAndSet(false, true)) {
            callback.onStopped()
        }
    }
}
