package com.omr.checker.data.database

import com.omr.checker.data.entity.SheetTemplateEntity
import com.omr.checker.data.model.BubbleGridConfig
import com.omr.checker.data.model.ElementType
import com.omr.checker.data.model.SheetElement
import com.omr.checker.data.model.SheetLayoutConfig

object DefaultTemplates {

    const val DEFAULT_60_ID = 1L

    fun createDefault60Layout(): SheetLayoutConfig {
        val elements = listOf(
            SheetElement(
                type = ElementType.TEST_NAME,
                label = "Test / Subject Name",
                x = 0.08f,
                y = 0.085f,
                width = 0.54f,
                height = 0.045f
            ),
            SheetElement(
                type = ElementType.ANSWER_SHEET_CODE,
                label = "Sheet Code",
                x = 0.65f,
                y = 0.085f,
                width = 0.27f,
                height = 0.045f
            ),
            SheetElement(
                type = ElementType.STUDENT_NAME,
                label = "Student Name",
                x = 0.08f,
                y = 0.138f,
                width = 0.54f,
                height = 0.04f
            ),
            SheetElement(
                type = ElementType.DATE,
                label = "Date",
                x = 0.65f,
                y = 0.138f,
                width = 0.27f,
                height = 0.04f
            ),
            SheetElement(
                type = ElementType.SECTION,
                label = "Grade & Section",
                x = 0.08f,
                y = 0.188f,
                width = 0.40f,
                height = 0.04f
            ),
            SheetElement(
                type = ElementType.BUBBLE_GRID,
                label = "Questions (1-60)",
                x = 0.08f,
                y = 0.240f,
                width = 0.84f,
                height = 0.680f,
                gridConfig = BubbleGridConfig(
                    startQuestion = 1,
                    endQuestion = 60,
                    options = listOf('A', 'B', 'C', 'D'),
                    columns = 3
                )
            )
        )

        return SheetLayoutConfig(
            pageWidth = 2480,
            pageHeight = 3508,
            cornerAnchorSizeFraction = 0.04f,
            elements = elements
        )
    }

    fun createDefault60TemplateEntity(): SheetTemplateEntity {
        return SheetTemplateEntity(
            id = DEFAULT_60_ID,
            name = "60-Item Answer Sheet (A-D)",
            totalItems = 60,
            optionsPerItem = 4,
            isDefault = true,
            layoutConfig = createDefault60Layout()
        )
    }
}
