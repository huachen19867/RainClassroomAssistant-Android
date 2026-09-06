package com.zaqizaba.rainassistant.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AnswerNormalizerTest {
    private val options = listOf(
        Question.Option("A", "甲"),
        Question.Option("B", "乙"),
        Question.Option("C", "丙"),
    )

    @Test
    fun mapsOptionTextAndPackedLetters() {
        val question = Question("1", 5, "", options, null)
        assertEquals(listOf("B", "A", "C"), AnswerNormalizer.normalize(question, listOf("乙", "AC")))
    }

    @Test
    fun singleChoiceKeepsOnlyFirstAnswer() {
        val question = Question("1", 1, "", options, null)
        assertEquals(listOf("B"), AnswerNormalizer.normalize(question, listOf("B", "C")))
    }

    @Test
    fun fillBlankPreservesOrderAndDuplicates() {
        val question = Question("1", 4, "", emptyList(), null)
        assertEquals(listOf("答案", "答案"), AnswerNormalizer.normalize(question, listOf("答案", "答案")))
    }
}

