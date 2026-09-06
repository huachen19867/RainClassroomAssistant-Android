package com.zaqizaba.rainassistant.model

object AnswerDelay {
    const val DEFAULT_SECONDS = 3
    const val MIN_SECONDS = 0
    const val MAX_SECONDS = 60

    fun normalize(seconds: Int): Int = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)
}
