package cn.shaohsmoni.voxassist

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full conversation loop on a background thread:
 *
 *   mic -> [KWS wake word]  --(keyword hit)-->  SESSION ON
 *       -> streaming ASR (partial + endpoint-based final)
 *       -> final text -> GLM chat -> answer text
 *       -> Edge TTS mp3 -> MediaPlayer (barge-in stoppable)
 *       -> (30s silence) -> SESSION OFF, back to wake-word listening
 *
 * While TTS is playing, VAD watches for speech onset -> barge-in: stop playback
 * and drop back to listening immediately.
 */
class VoiceEngine(
    private val context: Context,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onState(state: String)              // IDLE / LISTENING / THINKING / SPEAKING
        fun onPartial(text: String)
        fun onFinal(text: String)               // recognized user utterance
        fun onAnswer(text: String)              // GLM reply (before TTS)
        fun onError(msg: String)
    }

    companion object {
        const val SR = 16000
        const val SESSION_TIMEOUT_MS = 30_000L
        // barge-in via VAD also hears our own TTS through the mic and would cut
        // playback mid-sentence; disabled until AEC is wired up
        const val BARGE_IN_ENABLED = false
    }

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    // external objects (engine-owned lifecycle)
    private var asr: OnlineRecognizer? = null
    private var kws: KeywordSpotter? = null
    private var vad: Vad? = null
    private var asrStream: OnlineStream? = null
    private var kwsStream: OnlineStream? = null
    private var player: MediaPlayer? = null
    @Volatile private var speaking = false
    @Volatile private var sessionOn = false
    @Volatile private var speakStartAt = 0L
    private var lastSpeechAt = 0L
    private val ttsFile: File by lazy { File(context.filesDir, "tts.mp3") }

    // on-device TTS fallback, initialized in start()
    private var sysTts: android.speech.tts.TextToSpeech? = null
    @Volatile private var sysTtsReady = false

    fun start() {
        if (!running.compareAndSet(false, true)) return
        initSystemTts()
        thread = Thread({ loop() }, "vox-engine").apply { start() }
    }

    fun stop() {
        running.set(false)
        stopPlayback()
        try {
            sysTts?.stop()
        } catch (_: Exception) {
        }
        try {
            sysTts?.shutdown()
        } catch (_: Exception) {
        }
        sysTts = null
        sysTtsReady = false
        thread?.interrupt()
        thread = null
        releaseModels()
    }

    fun stopPlayback() {
        speaking = false
        try {
            sysTts?.stop()
        } catch (_: Exception) {
        }
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
    }

    private fun releaseModels() {
        try {
            asrStream?.release()
        } catch (_: Exception) {
        }
        try {
            kwsStream?.release()
        } catch (_: Exception) {
        }
        try {
            asr?.release()
        } catch (_: Exception) {
        }
        try {
            kws?.release()
        } catch (_: Exception) {
        }
        try {
            vad?.release()
        } catch (_: Exception) {
        }
        asrStream = null; kwsStream = null; asr = null; kws = null; vad = null
    }

    fun modelsReady(): Boolean {
        val d = context.filesDir
        return File(d, "asr-encoder.int8.onnx").exists() &&
                File(d, "silero_vad.onnx").exists() &&
                File(d, "kws-encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx").exists()
    }

    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(
            SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SR,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, SR)  // >= 1s ring
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            callbacks.onError("麦克风初始化失败（权限？）")
            running.set(false)
            return
        }

        val d = context.filesDir
        try {
            asr = OnlineRecognizer(
                assetManager = null,
                config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = "${d}/asr-encoder.int8.onnx",
                            decoder = "${d}/asr-decoder.int8.onnx",
                            joiner = "${d}/asr-joiner.int8.onnx",
                        ),
                        tokens = "${d}/tokens.txt",
                        numThreads = 2,
                        provider = "cpu",
                    ),
                    endpointConfig = EndpointConfig(
                        rule1 = EndpointRule(false, 2.4f, 0.0f),
                        rule2 = EndpointRule(true, 1.4f, 0.0f),
                        rule3 = EndpointRule(false, 0.0f, 20.0f),
                    ),
                ),
            )
            kws = KeywordSpotter(
                assetManager = null,
                config = KeywordSpotterConfig(
                    featConfig = FeatureConfig(),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = "${d}/kws-encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                            decoder = "${d}/kws-decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                            joiner = "${d}/kws-joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                        ),
                        tokens = "${d}/kws-tokens.txt",
                        numThreads = 1,
                        provider = "cpu",
                    ),
                    keywordsFile = "${d}/kws-keywords.txt",
                    keywordsScore = 1.5f,
                    keywordsThreshold = 0.25f,
                    numTrailingBlanks = 2,
                ),
            )
            vad = Vad(
                assetManager = null,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = "${d}/silero_vad.onnx",
                        threshold = 0.5f,
                        minSilenceDuration = 0.45f,
                        minSpeechDuration = 0.25f,
                        windowSize = 512,
                        maxSpeechDuration = 12.0f,
                    ),
                    sampleRate = SR,
                    numThreads = 1,
                ),
            )
            asrStream = asr!!.createStream("")
            kwsStream = kws!!.createStream("")
        } catch (e: Exception) {
            callbacks.onError("模型加载失败: ${e.message}")
            running.set(false)
            return
        }

        callbacks.onState("IDLE")
        rec.startRecording()

        val block = ShortArray(100)          // 100 samples = 6.25ms granularity
        val f32 = FloatArray(block.size)
        var lastPartial = ""

        try {
            while (running.get()) {
                val n = rec.read(block, 0, block.size)
                if (n <= 0) continue
                for (i in 0 until n) f32[i] = block[i] / 32768f
                val chunk = if (n == f32.size) f32 else f32.copyOf(n)

                // ---- 1) wake word always-on (only in IDLE) ----
                if (!sessionOn) {
                    kwsStream!!.acceptWaveform(chunk, SR)
                    while (kws!!.isReady(kwsStream!!)) kws!!.decode(kwsStream!!)
                    val kr = kws!!.getResult(kwsStream!!)
                    if (kr.keyword.isNotBlank()) {
                        sessionOn = true
                        lastSpeechAt = System.currentTimeMillis()
                        kws!!.reset(kwsStream!!)
                        callbacks.onState("LISTENING")
                        beep()
                    }
                }

                // ---- 2) VAD (speech presence for barge-in & session timeout) ----
                vad!!.acceptWaveform(chunk)
                var speechPresent = false
                while (!vad!!.empty()) {
                    vad!!.front()
                    vad!!.pop()
                    speechPresent = true
                    vad!!.clear()
                }
                if (speechPresent) lastSpeechAt = System.currentTimeMillis()

                // barge-in (disabled by default - see BARGE_IN_ENABLED)
                if (BARGE_IN_ENABLED && speaking && speechPresent &&
                    System.currentTimeMillis() - speakStartAt > 800) {
                    stopPlayback()
                    callbacks.onState("LISTENING")
                }

                // ---- 3) streaming ASR ----
                asrStream!!.acceptWaveform(chunk, SR)
                while (asr!!.isReady(asrStream!!)) asr!!.decode(asrStream!!)
                val text = asr!!.getResult(asrStream!!).text.trim()

                if (sessionOn && !speaking && text.isNotEmpty() && text != lastPartial) {
                    lastPartial = text
                    callbacks.onPartial(text)
                }

                if (sessionOn && asr!!.isEndpoint(asrStream!!)) {
                    if (text.isNotEmpty()) {
                        lastPartial = ""
                        callbacks.onFinal(text)
                        handleQuery(text)
                    } else {
                        // silence endpoint with nothing said -> maybe close session on timeout
                        maybeCloseSession()
                    }
                    asr!!.reset(asrStream!!)
                }

                if (sessionOn && !speaking) maybeCloseSession()
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            callbacks.onError("引擎异常: ${e.message}")
        } finally {
            try {
                rec.stop()
            } catch (_: Exception) {
            }
            rec.release()
            releaseModels()
        }
    }

    private fun maybeCloseSession() {
        if (System.currentTimeMillis() - lastSpeechAt > SESSION_TIMEOUT_MS) {
            sessionOn = false
            callbacks.onState("IDLE")
        }
    }

    /** local beep so the user hears the wake instantly, without network */
    private fun beep() {
        try {
            val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 60)
            tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 120)
            Thread {
                try { Thread.sleep(300) } catch (_: Exception) {}
                try { tg.release() } catch (_: Exception) {}
            }.start()
        } catch (_: Exception) {
        }
    }

    private fun handleQuery(query: String) {
        callbacks.onState("THINKING")
        Thread({
            try {
                val answer = GlmClient.chat(context, query)
                callbacks.onAnswer(answer)
                speak(answer)
            } catch (e: Exception) {
                callbacks.onError("大脑故障: ${e.message}")
                callbacks.onState("LISTENING")
            }
        }, "glm-query").start()
    }

    private fun speak(text: String) {
        callbacks.onState("SPEAKING")
        try {
            ttsFile.delete()
            val done = java.util.concurrent.CountDownLatch(1)
            val firstChunk = java.util.concurrent.atomic.AtomicBoolean(false)
            var localPlayer: MediaPlayer? = null

            EdgeTtsClient().synthesize(
                text,
                "zh-CN-XiaoxiaoNeural",
                "+20%",
                object : EdgeTtsClient.Sink {
                    override fun onAudio(mp3: ByteArray, isFinal: Boolean) {
                        if (mp3.isNotEmpty()) {
                            ttsFile.appendBytes(mp3)
                            if (firstChunk.compareAndSet(false, true) && !ttsFile.hasNoData()) {
                                // start playback as soon as we have some audio
                                localPlayer = startPlayer()
                            }
                        }
                        if (isFinal) done.countDown()
                    }

                    override fun onError(message: String) {
                        done.countDown()
                        // fall back: if nothing played, report
                        if (localPlayer == null) {
                            android.util.Log.e("VoxAssist", "TTS error: $message")
                        }
                    }

                    private fun startPlayer(): MediaPlayer {
                        val p = MediaPlayer()
                        p.setAudioAttributes(
                            android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        p.setDataSource(ttsFile.absolutePath)
                        p.setOnCompletionListener {
                            speaking = false
                            callbacks.onState(if (sessionOn) "LISTENING" else "IDLE")
                        }
                        p.prepare()
                        p.start()
                        speaking = true
                        speakStartAt = System.currentTimeMillis()
                        player = p
                        return p
                    }
                }
            )
            done.await()
            // if Edge TTS produced no audio, fall back to the on-device TTS engine
            if (localPlayer == null) {
                val ok = sysSay(text)
                if (!ok) {
                    callbacks.onError("Edge TTS 无音频且系统TTS不可用（检查网络/系统语音引擎）")
                    callbacks.onState(if (sessionOn) "LISTENING" else "IDLE")
                } else {
                    callbacks.onState("SPEAKING")
                }
            }
        } catch (e: Exception) {
            callbacks.onError("Edge TTS失败: ${e.message}，尝试系统TTS")
            if (!sysSay(text)) {
                callbacks.onState(if (sessionOn) "LISTENING" else "IDLE")
            } else {
                callbacks.onState("SPEAKING")
            }
        }
    }

    /** on-device TTS engine (EMUI/Huawei voice) used as a fallback when Edge TTS fails */
    private fun initSystemTts() {
        try {
            val t = android.speech.tts.TextToSpeech(context) { status ->
                sysTtsReady = status == android.speech.tts.TextToSpeech.SUCCESS
                if (sysTtsReady) {
                    try {
                        sysTts?.language = java.util.Locale.SIMPLIFIED_CHINESE
                        sysTts?.setSpeechRate(1.05f)
                        sysTts?.setOnUtteranceProgressListener(
                            object : android.speech.tts.UtteranceProgressListener() {
                                override fun onStart(utteranceId: String?) {}
                                override fun onDone(utteranceId: String?) {
                                    speaking = false
                                    callbacks.onState(if (sessionOn) "LISTENING" else "IDLE")
                                }

                                override fun onError(utteranceId: String?) {
                                    speaking = false
                                    callbacks.onState(if (sessionOn) "LISTENING" else "IDLE")
                                }
                            }
                        )
                    } catch (_: Exception) {
                    }
                }
            }
            sysTts = t
        } catch (e: Exception) {
            sysTts = null
            sysTtsReady = false
        }
    }

    private fun sysSay(text: String): Boolean {
        if (sysTts == null) initSystemTts()
        val t = sysTts ?: return false
        if (!sysTtsReady) return false
        return try {
            t.language = java.util.Locale.SIMPLIFIED_CHINESE
            val r = t.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "vox")
            if (r == android.speech.tts.TextToSpeech.SUCCESS) {
                speaking = true
                speakStartAt = System.currentTimeMillis()
                true
            } else {
                speaking = false
                false
            }
        } catch (e: Exception) {
            speaking = false
            false
        }
    }
}

private fun File.hasNoData(): Boolean = length() == 0L
