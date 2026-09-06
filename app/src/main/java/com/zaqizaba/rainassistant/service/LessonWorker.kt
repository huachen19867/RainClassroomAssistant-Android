package com.zaqizaba.rainassistant.service

import com.zaqizaba.rainassistant.BuildConfig
import com.zaqizaba.rainassistant.model.ActiveLesson
import com.zaqizaba.rainassistant.model.Question
import com.zaqizaba.rainassistant.model.UserInfo
import com.zaqizaba.rainassistant.model.WorkPhase
import com.zaqizaba.rainassistant.network.DeepSeekClient
import com.zaqizaba.rainassistant.network.HttpSupport
import com.zaqizaba.rainassistant.network.RainClassroomApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class LessonWorker(
    private val lesson: ActiveLesson,
    private val userInfo: UserInfo,
    private val rainApi: RainClassroomApi,
    private val deepSeekClient: DeepSeekClient,
    private val scope: CoroutineScope,
    private val report: (WorkPhase, String) -> Unit,
    private val onFinished: (String) -> Unit,
) {
    private val questions = ConcurrentHashMap<String, Question>()
    private val presentations = ConcurrentHashMap.newKeySet<String>()
    private val solving = ConcurrentHashMap.newKeySet<String>()
    private val answered = ConcurrentHashMap.newKeySet<String>()
    private val closed = AtomicBoolean(false)
    private var lessonToken = ""
    private var authorization = ""
    private var identityId: String? = null
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0

    val key: String = "${lesson.lessonId}:${lesson.classroomId}"

    fun start() {
        scope.launch(Dispatchers.IO) { connect() }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        reconnectJob?.cancel()
        socket?.close(1000, "service stopped")
    }

    private fun connect() {
        if (closed.get()) return
        try {
            report(WorkPhase.CHECKING_IN, "正在加入课程：${lesson.courseName}")
            val checkIn = rainApi.checkIn(lesson.lessonId)
            lessonToken = checkIn.lessonToken
            authorization = checkIn.authorization
            identityId = checkIn.identityId

            val request = Request.Builder()
                .url(BuildConfig.YUKETANG_BASE_URL.replace("https://", "wss://") + "/wsapp/")
                .header("Cookie", "sessionid=${rainApi.sessionId}")
                .header("Authorization", "Bearer $authorization")
                .build()
            socket = HttpSupport.client.newWebSocket(request, SocketListener())
        } catch (error: Exception) {
            report(WorkPhase.NETWORK_ERROR, "${lesson.courseName} 加入失败：${error.message}")
            scheduleReconnect()
        }
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempt = 0
            val hello = JSONObject()
                .put("op", "hello")
                .put("userid", identityId?.takeIf(String::isNotBlank) ?: userInfo.id)
                .put("role", "student")
                .put("auth", lessonToken)
                .put("lessonid", lesson.lessonId)
            webSocket.send(hello.toString())
            report(WorkPhase.COURSE_FOUND, "已签到并监听：${lesson.courseName}")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val data = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (data.optString("op")) {
                "hello" -> handleHello(webSocket, data)
                "presentationupdated", "presentationcreated" -> {
                    data.stringValue("presentation").takeIf(String::isNotBlank)?.let(::loadPresentation)
                }
                "slidenav" -> handleSlideNavigation(webSocket, data)
                "unlockproblem" -> {
                    val problem = data.optJSONObject("problem")
                    val problemId = problem?.problemId().orEmpty()
                    if (problemId.isNotBlank()) {
                        webSocket.send(problemInfoRequest(problemId))
                        solveAndSubmit(problemId)
                    }
                }
                "probleminfo" -> data.problemId().takeIf(String::isNotBlank)?.let(::solveAndSubmit)
                "lessonfinished" -> {
                    report(WorkPhase.MONITORING, "${lesson.courseName} 已下课，继续等待其他课程")
                    close()
                    onFinished(key)
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
            if (closed.get()) return
            report(WorkPhase.NETWORK_ERROR, "${lesson.courseName} 连接中断，正在恢复")
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!closed.get()) scheduleReconnect()
        }
    }

    private fun handleHello(webSocket: WebSocket, data: JSONObject) {
        val presentationIds = linkedSetOf<String>()
        data.stringValue("presentation").takeIf(String::isNotBlank)?.let(presentationIds::add)
        val timeline = data.optJSONArray("timeline") ?: JSONArray()
        for (index in 0 until timeline.length()) {
            val item = timeline.optJSONObject(index) ?: continue
            if (item.optString("type") == "slide") {
                item.stringValue("pres").takeIf(String::isNotBlank)?.let(presentationIds::add)
            }
        }
        presentationIds.forEach(::loadPresentation)

        val unlocked = data.optJSONArray("unlockedproblem") ?: JSONArray()
        for (index in 0 until unlocked.length()) {
            val problemId = unlocked.opt(index)?.toString().orEmpty()
            if (problemId.isNotBlank()) webSocket.send(problemInfoRequest(problemId))
        }
    }

    private fun handleSlideNavigation(webSocket: WebSocket, data: JSONObject) {
        val slide = data.optJSONObject("slide")
        slide?.stringValue("pres")?.takeIf(String::isNotBlank)?.let(::loadPresentation)
        val unlocked = data.optJSONArray("unlockedproblem") ?: JSONArray()
        for (index in 0 until unlocked.length()) {
            val problemId = unlocked.opt(index)?.toString().orEmpty()
            if (problemId.isNotBlank()) webSocket.send(problemInfoRequest(problemId))
        }
    }

    private fun loadPresentation(presentationId: String) {
        if (presentationId.isBlank() || !presentations.add(presentationId)) return
        scope.launch(Dispatchers.IO) {
            runCatching { rainApi.fetchPresentationQuestions(presentationId, authorization) }
                .onSuccess { rows -> rows.forEach { questions[it.id] = it } }
                .onFailure { presentations.remove(presentationId) }
        }
    }

    private fun solveAndSubmit(problemId: String) {
        if (problemId in answered || !solving.add(problemId)) return
        scope.launch(Dispatchers.IO) {
            try {
                var question = questions[problemId]
                if (question == null) {
                    presentations.toList().forEach { presentationId ->
                        rainApi.fetchPresentationQuestions(presentationId, authorization)
                            .forEach { questions[it.id] = it }
                    }
                    question = questions[problemId]
                }
                if (question == null) {
                    report(WorkPhase.NETWORK_ERROR, "收到题目 $problemId，但暂未取得题干")
                    return@launch
                }

                report(WorkPhase.SOLVING, "${lesson.courseName} 正在解题：$problemId")
                val answers = deepSeekClient.solve(question)
                if (answers.isEmpty()) {
                    report(WorkPhase.NETWORK_ERROR, "题目 $problemId 未得到有效答案")
                    return@launch
                }
                rainApi.answerProblem(problemId, question.type, answers, authorization)
                answered.add(problemId)
                report(WorkPhase.ANSWERED, "题目 $problemId 已提交：${answers.joinToString(", ")}")
            } catch (error: Exception) {
                report(WorkPhase.NETWORK_ERROR, "题目 $problemId 处理失败：${error.message}")
            } finally {
                solving.remove(problemId)
            }
        }
    }

    private fun scheduleReconnect() {
        if (closed.get() || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch(Dispatchers.IO) {
            val delaySeconds = minOf(30, 5 shl minOf(reconnectAttempt, 2))
            reconnectAttempt += 1
            kotlinx.coroutines.delay(delaySeconds * 1_000L)
            connect()
        }
    }

    private fun problemInfoRequest(problemId: String): String = JSONObject()
        .put("op", "probleminfo")
        .put("lessonid", lesson.lessonId)
        .put("problemid", problemId)
        .put("msgid", 1)
        .toString()

    private fun JSONObject.problemId(): String {
        for (key in listOf("problemId", "sid", "problemid", "id", "prob")) {
            stringValue(key).takeIf(String::isNotBlank)?.let { return it }
        }
        return ""
    }

    private fun JSONObject.stringValue(key: String): String = opt(key)?.toString().orEmpty()
}
