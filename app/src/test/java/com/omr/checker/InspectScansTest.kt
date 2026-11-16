package com.omr.checker

import org.junit.Test
import java.io.File
import java.sql.DriverManager

class InspectScansTest {

    @Test
    fun inspectAllScans() {
        val scratchDir = File("C:/Users/mannypallo/.gemini/antigravity-acp/brain/f324e581-c369-4d8e-ae51-6fd3921ff969/scratch")
        val dbFile = File(scratchDir, "omr_checker.db")
        if (!dbFile.exists()) return
        val outFile = File(scratchDir, "diagnostic_report.txt")
        val out = StringBuilder()

        try {
            Class.forName("org.sqlite.JDBC")
            val conn = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
            val stmt = conn.createStatement()

            out.appendLine("=== ALL SCANS IN DB ===")
            val rs = stmt.executeQuery("SELECT id, examId, score, totalPoints, percentage, scannedImagePath, rawAnswers, ambiguousQuestions FROM scan_results ORDER BY id DESC")
            while (rs.next()) {
                val id = rs.getLong("id")
                val score = "${rs.getFloat("score")}/${rs.getFloat("totalPoints")} (${rs.getFloat("percentage")}%)"
                val path = rs.getString("scannedImagePath") ?: ""
                val rawAnswers = rs.getString("rawAnswers") ?: ""
                val ambiguous = rs.getString("ambiguousQuestions") ?: ""

                out.appendLine("ID: $id | Exam: ${rs.getLong("examId")} | Score: $score | Image: ${File(path).name}")
                out.appendLine("  Answers: $rawAnswers")
                out.appendLine("  Ambiguous: $ambiguous")
            }
            conn.close()

        } catch (e: Exception) {
            out.appendLine("ERROR: ${e.message}")
            e.printStackTrace()
        }

        outFile.writeText(out.toString())
        println(out.toString())
    }
}
