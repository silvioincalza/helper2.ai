package ai.searchavo.eval

import ai.searchavo.shared.AudiencePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EverydayQueriesTest {

  private val queries = EvalFiles.searchQueries()
  private val everyday = queries.filter { it.group == "everyday" }

  @Test
  fun `the everyday group exists and is searched on the use AI path`() {
    assertEquals(21, everyday.size)
    assertTrue(everyday.all { it.path == AudiencePath.USE_AI }, "every everyday query uses USE_AI")
    assertTrue(everyday.all { it.id.matches(Regex("ev\\d{2}")) }, "everyday ids are ev01, ev02…")
  }

  @Test
  fun `query ids are unique across the whole set`() {
    assertEquals(queries.size, queries.map { it.id }.toSet().size)
  }
}
