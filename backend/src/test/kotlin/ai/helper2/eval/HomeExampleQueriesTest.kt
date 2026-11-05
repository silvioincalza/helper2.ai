package ai.helper2.eval

import ai.helper2.shared.AudiencePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The home examples are in the search set with the path they send (docs/eval/README.md). */
class HomeExampleQueriesTest {

  @Test
  fun `every home example query carries its audience path, and typed queries carry none`() {
    val queries = EvalFiles.searchQueries()
    val examples = queries.filter { it.group == "home-example" }

    assertEquals(11, examples.size)
    assertTrue(examples.all { it.path != null }, "an example is searched with its path")
    assertEquals(
      setOf(AudiencePath.USE_AI, AudiencePath.BUILD_WITH_AI),
      examples.mapNotNull { it.path }.toSet(),
    )
    assertTrue(queries.filter { it.group != "home-example" }.all { it.path == null })
    assertEquals(queries.size, queries.map { it.id }.toSet().size, "ids are unique")
  }
}
