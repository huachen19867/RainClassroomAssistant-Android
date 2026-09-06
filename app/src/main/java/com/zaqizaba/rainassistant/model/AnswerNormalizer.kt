package com.zaqizaba.rainassistant.model

object AnswerNormalizer {
    fun normalize(question: Question, raw: List<String>): List<String> {
        if (question.type == 4) return raw.map(String::trim).filter(String::isNotBlank)

        val valueToKey = question.options.associate { it.value.trim() to it.key.trim().uppercase() }
        val allowed = question.options.map { it.key.trim().uppercase() }.filter(String::isNotBlank).toSet()
        val normalized = raw.flatMap { answer ->
            val text = answer.trim()
            val direct = text.uppercase()
            when {
                direct in allowed -> listOf(direct)
                text in valueToKey -> listOfNotNull(valueToKey[text])
                else -> direct.filter { it.toString() in allowed }.map(Char::toString)
            }
        }.distinct()
        return if (question.type in setOf(1, 3, 6)) normalized.take(1) else normalized
    }
}

