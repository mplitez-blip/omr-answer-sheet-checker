package com.omr.checker.domain

import com.omr.checker.data.entity.ExamEntity

object ScoringEngine {

    data class ScoreSummary(
        val earnedScore: Float,
        val totalPoints: Float,
        val percentage: Float,
        val correctCount: Int,
        val incorrectCount: Int,
        val blankCount: Int,
        val itemResults: Map<Int, Boolean?> // true: correct, false: incorrect, null: blank
    )

    fun grade(
        exam: ExamEntity,
        rawAnswers: Map<Int, Char?>
    ): ScoreSummary {
        var earnedPoints = 0f
        var correctCount = 0
        var incorrectCount = 0
        var blankCount = 0
        val itemResults = mutableMapOf<Int, Boolean?>()

        val totalPoints = exam.answerKey.size * exam.pointsPerItem

        for ((question, correctAnswer) in exam.answerKey) {
            val studentAnswer = rawAnswers[question]
            when {
                studentAnswer == null -> {
                    blankCount++
                    itemResults[question] = null
                }
                studentAnswer.equals(correctAnswer, ignoreCase = true) -> {
                    correctCount++
                    earnedPoints += exam.pointsPerItem
                    itemResults[question] = true
                }
                else -> {
                    incorrectCount++
                    itemResults[question] = false
                }
            }
        }

        val percentage = if (totalPoints > 0f) (earnedPoints / totalPoints) * 100f else 0f

        return ScoreSummary(
            earnedScore = earnedPoints,
            totalPoints = totalPoints,
            percentage = percentage,
            correctCount = correctCount,
            incorrectCount = incorrectCount,
            blankCount = blankCount,
            itemResults = itemResults
        )
    }
}
