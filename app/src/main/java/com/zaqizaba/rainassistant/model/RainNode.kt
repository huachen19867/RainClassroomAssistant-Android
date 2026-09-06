package com.zaqizaba.rainassistant.model

data class RainNode(
    val key: String,
    val displayName: String,
    val host: String,
) {
    val baseUrl: String
        get() = "https://$host"

    val webSocketUrl: String
        get() = "wss://$host/wsapp/"
}

object RainNodes {
    const val DEFAULT_KEY = "www"

    val all: List<RainNode> = listOf(
        RainNode(DEFAULT_KEY, "雨课堂（主站）", "www.yuketang.cn"),
        RainNode("changjiang", "长江雨课堂", "changjiang.yuketang.cn"),
        RainNode("huanghe", "黄河雨课堂", "huanghe.yuketang.cn"),
        RainNode("pro", "荷塘雨课堂", "pro.yuketang.cn"),
    )

    fun fromKey(key: String?): RainNode = all.firstOrNull { it.key == key } ?: all.first()
}
