package cn.shaohsmoni.voxassist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Setup UI: permissions, model download, GLM key, start/stop service.
 * Log pane shows engine state in real time.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var btn: Button

    private val neededModels = listOf(
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16.tar.bz2" to "asr.tar.bz2",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx" to "silero_vad.onnx",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile.tar.bz2" to "kws.tar.bz2",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this

        val pad = (16 * resources.displayMetrics.density).toInt()
        val cfg0 = GlmClient.loadConfig(ctx)
        val baseEdit = EditText(this).apply {
            hint = "Base URL（如 http://106.14.11.173:14201/v1）"
            setText(cfg0.baseUrl)
        }
        val keyEdit = EditText(this).apply {
            hint = "API Key（OpenClaw 网关 token）"
            setText(cfg0.apiKey)
        }
        val modelEdit = EditText(this).apply {
            hint = "模型（OpenClaw 填 openclaw）"
            setText(cfg0.model)
        }
        log = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF9EE49E.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
        }
        scroll = ScrollView(this).apply { addView(log) }
        btn = Button(this).apply { text = "启动语音助手" }
        btn.setOnClickListener {
            GlmClient.saveConfig(
                ctx,
                GlmClient.Config(
                    baseEdit.text.toString().trim(),
                    keyEdit.text.toString().trim(),
                    modelEdit.text.toString().trim()
                )
            )
            startVoice()
        }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(baseEdit)
            addView(keyEdit)
            addView(modelEdit)
            addView(btn)
            addView(scroll, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(layout)

        appendLog("Vox助手 v1.0 —— 全离线语音助手\n")
        // show engine state/errors coming from the foreground service
        AppLog.listener = { s -> appendLog(s) }
        AppLog.snapshot().forEach { appendLog(it) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        } else {
            prepareAssets()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            prepareAssets()
        } else {
            appendLog("✗ 麦克风权限被拒绝，语音功能不可用\n")
        }
    }

    override fun onDestroy() {
        AppLog.listener = null
        super.onDestroy()
    }

    private fun prepareAssets() {
        val d = filesDir
        if (VoiceEngine(this, object : VoiceEngine.Callbacks {
                override fun onState(state: String) {}
                override fun onPartial(text: String) {}
                override fun onFinal(text: String) {}
                override fun onAnswer(text: String) {}
                override fun onError(msg: String) {}
            }).modelsReady()) {
            appendLog("✓ 模型已就绪，可直接启动\n")
            return
        }
        appendLog("首次运行：下载模型（约 55MB，仅此一次）...\n")
        Thread {
            try {
                val BASE = "https://github.com/myyssfb/voxassist/releases/download/models-v1"
                // 1) ASR (int8)
                downloadIfNeeded("$BASE/asr-encoder.int8.onnx", File(d, "asr-encoder.int8.onnx"))
                downloadIfNeeded("$BASE/asr-decoder.int8.onnx", File(d, "asr-decoder.int8.onnx"))
                downloadIfNeeded("$BASE/asr-joiner.int8.onnx", File(d, "asr-joiner.int8.onnx"))
                downloadIfNeeded("$BASE/tokens.txt", File(d, "tokens.txt"))

                // 2) VAD
                downloadIfNeeded(neededModels[1].first, File(d, "silero_vad.onnx"))

                // 3) KWS
                downloadIfNeeded("$BASE/kws-encoder.int8.onnx", File(d, "kws-encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"))
                downloadIfNeeded("$BASE/kws-decoder-epoch-12-avg-2-chunk-16-left-64.onnx", File(d, "kws-decoder-epoch-12-avg-2-chunk-16-left-64.onnx"))
                downloadIfNeeded("$BASE/kws-joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx", File(d, "kws-joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"))
                downloadIfNeeded("$BASE/kws-tokens.txt", File(d, "kws-tokens.txt"))

                // 4) keywords file (built-in) — always rewrite to latest format
                val kw = File(d, "kws-keywords.txt")
                kw.writeText(KEYWORDS)

                runOnUiThread { appendLog("✓ 模型全部就绪！点启动即可\n") }
            } catch (e: Exception) {
                runOnUiThread { appendLog("✗ 下载失败: ${e.message}\n") }
            }
        }.start()
    }

    private fun downloadIfNeeded(url: String, dest: File) {
        if (dest.exists() && dest.length() > 0) return
        download(url, dest)
    }

    private fun download(url: String, dest: File) {
        appendLog("↓ ${dest.name} ...\n")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.connect()
        val input = if (conn.responseCode in 300..399) {
            val loc = conn.getHeaderField("Location")
            (URL(loc).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000; readTimeout = 60000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }.inputStream
        } else conn.inputStream
        input.use { ins -> dest.outputStream().use { ins.copyTo(it, 1 shl 16) } }
        appendLog("  ✓ ${dest.name} (${dest.length() / 1048576}MB)\n")
    }

    private fun startVoice() {
        appendLog("▶ 启动引擎...\n")
        val intent = Intent(this, VoiceService::class.java)
        ContextCompat.startForegroundService(this, intent)
        btn.isEnabled = false
        btn.text = "运行中（通知栏可停止）"
    }

    fun appendLog(s: String) {
        runOnUiThread {
            log.append(s)
            scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    companion object {
        // 你好小爱 + few fallbacks; format: token sequence @score
        val KEYWORDS = """
            n ǐ h ǎo x iǎo ài @你好小爱
            x iǎo ài t óng x ué @小爱同学
        """.trimIndent()
    }
}
