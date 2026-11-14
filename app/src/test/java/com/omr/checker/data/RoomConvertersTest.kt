package com.omr.checker.data

import com.omr.checker.data.converter.RoomConverters
import com.omr.checker.data.database.DefaultTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RoomConvertersTest {

    private val converters = RoomConverters()

    @Test
    fun testSheetLayoutConfigSerialization() {
        val original = DefaultTemplates.createDefault60Layout()
        val json = converters.fromSheetLayoutConfig(original)
        assertNotNull(json)

        val restored = converters.toSheetLayoutConfig(json)
        assertEquals(original.elements.size, restored.elements.size)
        assertEquals(original.pageWidth, restored.pageWidth)
        assertEquals(original.elements[0].label, restored.elements[0].label)
    }

    @Test
    fun testAnswerKeyMapSerialization() {
        val answerKey = mapOf(1 to 'A', 2 to 'B', 3 to 'C', 4 to 'D')
        val json = converters.fromAnswerKeyMap(answerKey)
        val restored = converters.toAnswerKeyMap(json)

        assertEquals(answerKey, restored)
    }

    @Test
    fun testRawAnswersMapSerializationWithNulls() {
        val answers = mapOf<Int, Char?>(1 to 'A', 2 to null, 3 to 'C')
        val json = converters.fromRawAnswersMap(answers)
        val restored = converters.toRawAnswersMap(json)

        assertEquals('A', restored[1])
        assertNull(restored[2])
        assertEquals('C', restored[3])
    }

    @Test
    fun testIntListSerialization() {
        val list = listOf(5, 12, 45)
        val json = converters.fromIntList(list)
        val restored = converters.toIntList(json)

        assertEquals(list, restored)
    }
}
