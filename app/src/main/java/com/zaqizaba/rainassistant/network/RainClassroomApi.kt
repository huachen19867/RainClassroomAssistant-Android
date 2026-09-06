package com.zaqizaba.rainassistant.network

import com.zaqizaba.rainassistant.model.ActiveLesson
import com.zaqizaba.rainassistant.model.CheckInResult
import com.zaqizaba.rainassistant.model.Question
import com.zaqizaba.rainassistant.model.UserInfo
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class RainClassroomApi(
    val sessionId: String,
    baseUrl: String,
) {
    val baseUrl = baseUrl.trimEnd('/')
    val webSocketUrl = baseUrl
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://") + "/wsapp/"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun validateSession(): UserInfo {
        val payload = executeJson(
            Request.Builder()
                .url("$baseUrl/api/v3/user/basic-info")
                .get()
                .rainHeaders()
                .build(),
        )
        ensureSuccess(payload, "校验登录状态失败")
        val data = payload.optJSONObject("data") ?: JSONObject()
        return UserInfo(
            id = data.opt("id")?.toString().orEmpty(),
            name = data.optString("name"),
        )
    }

    fun getOnLessons(): List<ActiveLesson> {
        val payload = executeJson(
            Request.Builder()
                .url("$baseUrl/api/v3/classroom/on-lesson")
                .get()
                .rainHeaders()
                .build(),
        )
        ensureSuccess(payload, "读取正在上课列表失败")
        val rows = payload.optJSONObject("data")
            ?.optJSONArray("onLessonClassrooms") ?: JSONArray()
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val lessonId = row.stringValue("lessonId")
                val classroomId = row.stringValue("classroomId")
                if (lessonId.isBlank() || classroomId.isBlank()) continue
                add(
                    ActiveLesson(
                        lessonId = lessonId,
                        classroomId = classroomId,
                        courseName = row.optString("courseName", "未命名课程"),
                    ),
                )
            }
        }
    }

    fun checkIn(lessonId: String): CheckInResult {
        val body = JSONObject()
            .put("source", 5)
            .put("lessonId", lessonId)
            .toString()
            .toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/v3/lesson/checkin")
            .post(body)
            .rainHeaders()
            .build()

        HttpSupport.client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (response.code == 401 || response.code == 403) {
                    throw SessionExpiredException("雨课堂登录已失效")
                }
                throw ApiException("签到失败：HTTP ${response.code}")
            }
            val payload = JSONObject(text)
            ensureSuccess(payload, "签到失败")
            val data = payload.optJSONObject("data")
                ?: throw ApiException("签到响应缺少 data")
            val lessonToken = data.optString("lessonToken")
            val authorization = response.header("Set-Auth").orEmpty()
            if (lessonToken.isBlank() || authorization.isBlank()) {
                throw ApiException("签到响应缺少课堂授权信息")
            }
            return CheckInResult(
                lessonToken = lessonToken,
                authorization = authorization,
                identityId = data.opt("identityId")?.toString(),
            )
        }
    }

    fun fetchPresentationQuestions(
        presentationId: String,
        authorization: String,
    ): List<Question> {
        val request = Request.Builder()
            .url("$baseUrl/api/v3/lesson/presentation/fetch?presentation_id=$presentationId")
            .get()
            .rainHeaders(authorization)
            .build()
        val payload = executeJson(request)
        ensureSuccess(payload, "读取课堂题目失败")
        val slides = payload.optJSONObject("data")?.optJSONArray("slides") ?: return emptyList()
        return buildList {
            for (index in 0 until slides.length()) {
                val slide = slides.optJSONObject(index) ?: continue
                val rawProblem = slide.optJSONObject("problem") ?: continue
                val problemId = rawProblem.stringValue("problemId")
                if (problemId.isBlank()) continue
                val type = rawProblem.optInt("problemType", 1)
                val content = rawProblem.opt("content")
                val body = when (content) {
                    is JSONObject -> content.optString("text").ifBlank { rawProblem.optString("body") }
                    is String -> content.ifBlank { rawProblem.optString("body") }
                    else -> rawProblem.optString("body")
                }
                val optionsJson = rawProblem.optJSONArray("options") ?: JSONArray()
                val options = buildList {
                    for (optionIndex in 0 until optionsJson.length()) {
                        val option = optionsJson.optJSONObject(optionIndex) ?: continue
                        add(
                            Question.Option(
                                key = option.optString("key"),
                                value = option.optString("value"),
                            ),
                        )
                    }
                }
                val questionImage = when (content) {
                    is JSONObject -> content.optString("img").takeIf(String::isNotBlank)
                        ?: content.optJSONArray("images")?.optString(0)?.takeIf(String::isNotBlank)
                    else -> null
                }
                add(
                    Question(
                        id = problemId,
                        type = type,
                        body = body,
                        options = options,
                        imageUrl = questionImage ?: slide.optString("cover").takeIf(String::isNotBlank),
                    ),
                )
            }
        }
    }

    fun answerProblem(
        problemId: String,
        problemType: Int,
        answers: List<String>,
        authorization: String,
    ) {
        val result = JSONArray().apply { answers.forEach(::put) }
        val body = JSONObject()
            .put("problemId", problemId)
            .put("problemType", problemType)
            .put("dt", System.currentTimeMillis())
            .put("result", result)
            .toString()
            .toRequestBody(jsonMediaType)
        val payload = executeJson(
            Request.Builder()
                .url("$baseUrl/api/v3/lesson/problem/answer")
                .post(body)
                .rainHeaders(authorization)
                .build(),
        )
        val message = payload.optString("msg")
        if (payload.optInt("code", -1) != 0 && !message.contains("ALREADY_ANSWERED")) {
            throw ApiException("提交答案失败：${message.ifBlank { payload.optInt("code").toString() }}")
        }
    }

    private fun executeJson(request: Request): JSONObject {
        HttpSupport.client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (response.code == 401 || response.code == 403) {
                    throw SessionExpiredException("雨课堂登录已失效")
                }
                throw ApiException("雨课堂接口 HTTP ${response.code}")
            }
            return runCatching { JSONObject(text) }
                .getOrElse { throw ApiException("雨课堂返回了无法解析的数据") }
        }
    }

    private fun ensureSuccess(payload: JSONObject, prefix: String) {
        val code = payload.optInt("code", -1)
        if (code == 0) return
        val message = payload.optString("msg")
        val lower = message.lowercase()
        if (code == 401 || code == 403 || listOf("session", "login", "登录", "失效", "过期")
                .any(lower::contains)
        ) {
            throw SessionExpiredException("雨课堂登录已失效：$message")
        }
        throw ApiException("$prefix（$code）：$message")
    }

    private fun Request.Builder.rainHeaders(authorization: String = ""): Request.Builder {
        header("Cookie", "sessionid=$sessionId")
        header("User-Agent", ANDROID_USER_AGENT)
        if (authorization.isNotBlank()) {
            header("Authorization", "Bearer $authorization")
        }
        return this
    }

    private fun JSONObject.stringValue(key: String): String = opt(key)?.toString().orEmpty()

    companion object {
        private const val ANDROID_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/121 Mobile Safari/537.36"
    }
}
