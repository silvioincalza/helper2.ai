package ai.helper2.shared

import ai.helper2.guide.READING_TYPES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VocabularyTest {

    @Test
    fun `every type has its own specificity with no gaps`() {
        assertEquals(ResourceType.entries.indices.toList(), ResourceType.entries.map { it.specificity }.sorted())
    }

    @Test
    fun `a course wins over reading types and loses to tools when sources disagree`() {
        val course = ResourceType.COURSE.specificity
        assertTrue(course > ResourceType.LIBRARY.specificity)
        for (t in listOf(ResourceType.EVENT, ResourceType.PAPER, ResourceType.NEWS, ResourceType.ARTICLE, ResourceType.OTHER)) {
            assertTrue(course < t.specificity, "COURSE before $t")
        }
    }

    @Test
    fun `a course is not a reading type`() {
        assertFalse(ResourceType.COURSE in READING_TYPES)
    }
}
