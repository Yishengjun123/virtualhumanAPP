package com.example.virhuman.ai.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.util.concurrent.Executors

class SherpaTtsEngine(context: Context) : TtsEngine {
    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var ready = false

    init {
        try {
            val vitsConfig = OfflineTtsVitsModelConfig().apply {
                model = "sherpa/tts/model.onnx"
                lexicon = "sherpa/tts/lexicon.txt"
                tokens = "sherpa/tts/tokens.txt"
                dataDir = ""
                dictDir = "sherpa/tts/dict"
                noiseScale = 0.667f
                noiseScaleW = 0.8f
                lengthScale = 1.0f
            }

            val modelConfig = OfflineTtsModelConfig().apply {
                vits = vitsConfig
                numThreads = 2
                debug = false
                provider = "cpu"
            }

            val config = OfflineTtsConfig().apply {
                model = modelConfig
                // Enable FST-based text normalization (date/number/heteronym/phone)
                ruleFsts = "sherpa/tts/phone.fst,sherpa/tts/date.fst,sherpa/tts/number.fst,sherpa/tts/new_heteronym.fst"
            }

            tts = OfflineTts(context.assets, config)
            ready = true
        } catch (_: Exception) {
            ready = false
        }
    }

    override fun speak(text: String): Boolean {
        if (!ready || text.isBlank()) return false
        val localTts = tts ?: return false
        executor.execute {
            try {
                val generated = localTts.generate(text, 0, 1.0f)
                val samples = generated.samples
                if (samples.isEmpty()) return@execute

                val pcm = ShortArray(samples.size)
                for (i in samples.indices) {
                    val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
                    pcm[i] = v.toShort()
                }

                val sampleRate = generated.sampleRate
                val minBuffer = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                stop()
                audioTrack = AudioTrack(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                    maxOf(minBuffer, pcm.size * 2),
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )
                audioTrack?.play()
                audioTrack?.write(pcm, 0, pcm.size)
            } catch (_: Exception) {
            }
        }
        return true
    }

    override fun isReady(): Boolean = ready

    override fun stop() {
        try {
            audioTrack?.stop()
        } catch (_: Exception) {
        }
        audioTrack?.release()
        audioTrack = null
    }

    override fun release() {
        stop()
        tts?.release()
        tts = null
        ready = false
        executor.shutdownNow()
    }
}

