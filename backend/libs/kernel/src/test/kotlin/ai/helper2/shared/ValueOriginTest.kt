package ai.helper2.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class ValueOriginTest {

  private val writtenByAi =
    mapOf(
      ValueOrigin.SOURCE_PROVIDED to false,
      ValueOrigin.AI_EXTRACTED to true,
      ValueOrigin.AI_SUMMARY to true,
      ValueOrigin.AI_ESTIMATED to true,
      ValueOrigin.SELF_DECLARED to false,
      ValueOrigin.CURATED to false,
      ValueOrigin.COMMUNITY to false,
      ValueOrigin.DEMO to false,
    )

  @Test
  fun `every origin states whether an AI model wrote the value`() {
    assertEquals(ValueOrigin.entries.toSet(), writtenByAi.keys)
    assertEquals(writtenByAi, ValueOrigin.entries.associateWith { it.isAiWritten })
  }

  @Test
  fun `no origin changes side from the name rule it replaces`() {
    assertEquals(
      ValueOrigin.entries.associateWith { it.name.startsWith("AI_") },
      ValueOrigin.entries.associateWith { it.isAiWritten },
    )
  }
}
