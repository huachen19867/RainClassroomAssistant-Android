package com.zaqizaba.rainassistant.model

import org.json.JSONObject

data class ActiveLesson(
    val lessonId: String,
    val classroomId: String,
    val courseName: String,
)

data class UserInfo(
    val id: String,
    val name: String,
)

data class CheckInResult(
    val lessonToken: String,
    val authorization: String,
    val identityId: String?,
)

data class Question(
    val id: String,
    val type: Int,
    val body: String,
    val options: List<Option>,
    val imageUrl: String?,
) {
    data class Option(val key: String, val value: String)

    fun toJson(): JSONObject = JSONObject().apply {
        put("problemId", id)
        put("problemType", type)
        put("body", body)
    }
}

enum class WorkPhase(
    val title: String,
    val canAnswer: Boolean,
) {
    STOPPED("未启动", false),
    NEED_LOGIN("需要登录", false),
    NEED_API("需要 API Key", false),
    VALIDATING_LOGIN("正在校验登录", false),
    READY("已就绪", true),
    MONITORING("正在监听", true),
    COURSE_FOUND("检测到课程", true),
    CHECKING_IN("正在签到", true),
    WAITING_TO_SOLVE("答题等待中", true),
    SOLVING("正在解题", true),
    ANSWERED("已完成答题", true),
    NETWORK_ERROR("网络异常", false),
    SESSION_EXPIRED("登录已失效", false),
}

data class WorkStatus(
    val phase: WorkPhase,
    val detail: String,
    val updatedAt: Long = System.currentTimeMillis(),
)
