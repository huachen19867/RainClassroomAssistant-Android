package com.zaqizaba.rainassistant.network

import com.zaqizaba.rainassistant.BuildConfig
import com.zaqizaba.rainassistant.model.AnswerNormalizer
import com.zaqizaba.rainassistant.model.Question
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class DeepSeekClient(
    private val apiKey: String,
) {
    fun testConnection(): String {
        if (apiKey.isBlank()) throw ApiException("未配置 DeepSeek API Key")
        val request = Request.Builder()
            .url("${BuildConfig.DEEPSEEK_BASE_URL}/models")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        HttpSupport.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ApiException("DeepSeek API 测试失败：HTTP ${response.code}")
            }
            val payload = JSONObject(response.body?.string().orEmpty())
            val models = payload.optJSONArray("data") ?: JSONArray()
            val found = (0 until models.length()).any {
                models.optJSONObject(it)?.optString("id") == BuildConfig.DEEPSEEK_MODEL
            }
            if (!found) throw ApiException("账号未开放 ${BuildConfig.DEEPSEEK_MODEL}")
            return BuildConfig.DEEPSEEK_MODEL
        }
    }

    fun solve(question: Question): List<String> {
        if (apiKey.isBlank()) throw ApiException("未配置 DeepSeek API Key")
        val optionText = question.options.joinToString("\n") { "${it.key}: ${it.value}" }
        val userText = """
            请解答下面的课堂题目。题型代码：${question.type}
            题目：${question.body}
            选项：
            $optionText

            只返回 JSON，不要 Markdown：{"answers":["A"]}
            选择题答案使用选项字母；多选可返回多个字母；填空题按空格顺序返回文字答案。
        """.trimIndent()

        val userContent = JSONArray().put(JSONObject().put("type", "text").put("text", userText))
        question.imageUrl?.takeIf { it.startsWith("http") }?.let { imageUrl ->
            userContent.put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", imageUrl)),
            )
        }

        val requestJson = JSONObject()
            .put("model", BuildConfig.DEEPSEEK_MODEL)
            .put("temperature", 0.1)
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", "你是严谨的课堂题目求解器，只能输出 JSON 对象。"),
                    )
                    .put(JSONObject().put("role", "user").put("content", userContent)),
            )

        val request = Request.Builder()
            .url("${BuildConfig.DEEPSEEK_BASE_URL}/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val content = HttpSupport.client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }
                    .getOrNull()
                throw ApiException("DeepSeek 解题失败：${detail?.takeIf(String::isNotBlank) ?: "HTTP ${response.code}"}")
            }
            val payload = JSONObject(raw)
            payload.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()
        }

        val parsed = extractJson(content)
        val rawAnswers = parsed.optJSONArray("answers") ?: JSONArray()
        val answers = buildList {
            for (index in 0 until rawAnswers.length()) {
                rawAnswers.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }
        return AnswerNormalizer.normalize(question, answers)
    }

    private fun extractJson(text: String): JSONObject {
        val withoutThinking = text.replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "").trim()
        runCatching { return JSONObject(withoutThinking) }
        val start = withoutThinking.indexOf('{')
        val end = withoutThinking.lastIndexOf('}')
        if (start >= 0 && end > start) {
            runCatching { return JSONObject(withoutThinking.substring(start, end + 1)) }
        }
        throw ApiException("模型未返回可解析的 JSON 答案")
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
