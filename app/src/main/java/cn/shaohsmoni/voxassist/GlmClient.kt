package cn.shaohsmoni.voxassist

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Minimal OpenAI-compatible chat client for the GLM endpoint.
 * Base URL + key are stored in filesDir/config.json (written by MainActivity).
 */
object GlmClient {

    data class Config(val baseUrl: String, val apiKey: String, val model: String)

    fun loadConfig(context: Context): Config {
        val f = File(context.filesDir, "config.json")
        if (!f.exists()) {
            // sane defaults; key must be filled by user in the app UI
            return Config(
                baseUrl = "http://106.14.11.173:14201/v1",
                apiKey = "",
                model = "openclaw"
            )
        }
        val o = JSONObject(f.readText())
        return Config(
            baseUrl = o.optString("baseUrl", "http://106.14.11.173:14201/v1"),
            apiKey = o.optString("apiKey", ""),
            model = o.optString("model", "openclaw")
        )
    }

    fun saveConfig(context: Context, c: Config) {
        val o = JSONObject()
            .put("baseUrl", c.baseUrl)
            .put("apiKey", c.apiKey)
            .put("model", c.model)
        File(context.filesDir, "config.json").writeText(o.toString())
    }

    /** Single-turn chat; returns assistant text. */
    fun chat(context: Context, userText: String): String {
        val cfg = loadConfig(context)
        require(cfg.apiKey.isNotBlank()) { "未配置 GLM API Key" }

        val body = JSONObject()
            .put("model", cfg.model)
            .put(
                "messages", JSONArray()
                    .put(
                        JSONObject().put("role", "system").put(
                            "content",
                            "你是手机里的语音助手，回答要口语化、简短（一两句话），不要用 markdown、列表或表情。"
                        )
                    )
                    .put(JSONObject().put("role", "user").put("content", userText))
            )
            .put("max_tokens", 300)

        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        val req = okhttp3.Request.Builder()
            .url("${cfg.baseUrl.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .header("Content-Type", "application/json")
            .post(okhttp3.RequestBody.create(
                "application/json".toMediaType(), body.toString()))
            .build()

        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${text.take(200)}")
            val o = JSONObject(text)
            return o.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
        }
    }
}
