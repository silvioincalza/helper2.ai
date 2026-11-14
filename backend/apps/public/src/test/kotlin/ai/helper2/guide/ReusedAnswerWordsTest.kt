package ai.helper2.guide

import ai.helper2.search.NOW
import ai.helper2.search.Notice
import ai.helper2.search.NoticeCode
import ai.helper2.search.SearchOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ReusedAnswerWordsTest {

  private val comparison =
    GuideAnswer(
      status = GuideStatus.OK,
      searchQuery = "",
      recommendations = emptyList(),
      modelUsed = true,
      candidatesConsidered = 2,
      rejected = 0,
      search = SearchOutcome.empty(NOW),
      generatedAt = NOW,
      constraints = listOf("open-source", "runs offline"),
      notices =
        listOf(
          Notice(NoticeCode.GUIDE_SUBJECT_NOT_FOUND, "Not found.", subject = "LM-Studio"),
          Notice(NoticeCode.GUIDE_OUT_OF_TIME, "Out of time."),
        ),
      intent = GuideIntent.COMPARE,
      subjects = listOf("Ollama", "LM-Studio"),
      comparison =
        Comparison(
          listOf(ComparedSubject("s1", "Ollama", null), ComparedSubject("s2", "LM-Studio", null)),
          emptyList(),
        ),
    )

  @Test
  fun `every name of the answer is written as the second asker wrote it, in the answer's own columns`() {
    val reused =
      comparison.inTheWordsOf(
        listOf("lm studio", "OLLAMA"),
        listOf("Runs Offline", "open source"),
      )!!

    assertEquals(listOf("OLLAMA", "lm studio"), reused.subjects)
    assertEquals(listOf("open source", "Runs Offline"), reused.constraints)
    assertEquals(
      listOf("s1" to "OLLAMA", "s2" to "lm studio"),
      reused.comparison!!.subjects.map { it.key to it.name },
    )
    assertEquals(listOf("lm studio", null), reused.notices.map { it.subject })
    val everyField = reused.toString()
    listOf("Ollama", "LM-Studio", "open-source", "runs offline").forEach {
      assertFalse(it in everyField, "the first asker's \"$it\"")
    }
  }

  @Test
  fun `an answer with a name the second asker did not write is not reused`() {
    assertNull(
      comparison.inTheWordsOf(listOf("ollama", "jan"), listOf("runs offline", "open source"))
    )
    assertNull(comparison.inTheWordsOf(listOf("ollama", "lm studio"), listOf("open source")))
    assertNull(comparison.inTheWordsOf(listOf("ollama"), listOf("runs offline", "open source")))
  }
}
