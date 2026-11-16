package com.omr.checker.data

import com.omr.checker.data.entity.ExamEntity
import com.omr.checker.domain.ScoringEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class ScoringTest {

    @Test
    fun testScoreCalculation_AllCorrect() {
        val exam = ExamEntity(
            id = 1,
            templateId = 1,
            title = "Math 101",
            sheetCode = "MATH-01",
            answerKey = mapOf(1 to 'A', 2 to 'B', 3 to 'C', 4 to 'D'),
            pointsPerItem = 2.0f
        )

        val studentAnswers = mapOf<Int, Char?>(1 to 'A', 2 to 'B', 3 to 'C', 4 to 'D')
        val summary = ScoringEngine.grade(exam, studentAnswers)

        assertEquals(8.0f, summary.earnedScore, 0.001f)
        assertEquals(8.0f, summary.totalPoints, 0.001f)
        assertEquals(100.0f, summary.percentage, 0.001f)
        assertEquals(4, summary.correctCount)
        assertEquals(0, summary.incorrectCount)
        assertEquals(0, summary.blankCount)
    }

    @Test
    fun testScoreCalculation_PartialAndBlank() {
        val exam = ExamEntity(
            id = 1,
            templateId = 1,
            title = "Science",
            sheetCode = "SCI-01",
            answerKey = mapOf(1 to 'A', 2 to 'B', 3 to 'C', 4 to 'D'),
            pointsPerItem = 1.0f
        )

        val studentAnswers = mapOf<Int, Char?>(
            1 to 'A', // correct (1)
            2 to 'A', // wrong (0)
            3 to null, // blank (0)
            4 to 'd'  // correct, case insensitive (1)
        )
        val summary = ScoringEngine.grade(exam, studentAnswers)

        assertEquals(2.0f, summary.earnedScore, 0.001f)
        assertEquals(4.0f, summary.totalPoints, 0.001f)
        assertEquals(50.0f, summary.percentage, 0.001f)
        assertEquals(2, summary.correctCount)
        assertEquals(1, summary.incorrectCount)
        assertEquals(1, summary.blankCount)
    }

    @Test
    fun testScoreCalculation_UnassignedItemsIgnored_50outOf60() {
        // Teacher configures only 50 answer keys on a 60-item sheet
        val key = (1..50).associateWith { 'A' }
        val exam = ExamEntity(
            id = 2,
            templateId = 1,
            title = "50-Question Quiz",
            sheetCode = "QUIZ-50",
            answerKey = key,
            pointsPerItem = 1.0f
        )

        // Student answered 1..60, but questions 51..60 have no answer key
        val studentAnswers = (1..60).associateWith { 'A' as Char? }
        val summary = ScoringEngine.grade(exam, studentAnswers)

        assertEquals(50.0f, summary.earnedScore, 0.001f)
        assertEquals(50.0f, summary.totalPoints, 0.001f)
        assertEquals(100.0f, summary.percentage, 0.001f)
        assertEquals(50, summary.correctCount)
        assertEquals(0, summary.incorrectCount)
        assertEquals(0, summary.blankCount)
        // Ensure questions 51..60 are completely omitted from grading results
        assertEquals(false, summary.itemResults.containsKey(51))
        assertEquals(false, summary.itemResults.containsKey(60))
    }
}
