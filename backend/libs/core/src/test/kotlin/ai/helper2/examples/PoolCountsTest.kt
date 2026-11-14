package ai.helper2.examples

import ai.helper2.shared.AudiencePath
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class PoolCountsTest {

  private fun example(
    path: AudiencePath = AudiencePath.USE_AI,
    status: ExampleStatus = ExampleStatus.ACTIVE,
    outcome: CheckOutcome = CheckOutcome.PASS,
  ) =
    HomeExample(
      id = UUID.randomUUID(),
      label = "label",
      query = "query",
      path = path,
      locale = "en",
      status = status,
      expectedResourceIds = listOf(UUID.randomUUID()),
      origin = ExampleOrigin.CURATED,
      model = null,
      check = CheckResult(outcome),
      version = 0,
      updatedAt = Instant.EPOCH,
    )

  @Test
  fun `an empty pool lists every path and every hiding check at 0`() {
    val counts = PoolCounts.of(emptyList())
    assertEquals(mapOf(AudiencePath.USE_AI to 0, AudiencePath.BUILD_WITH_AI to 0), counts.shown)
    assertEquals(
      mapOf(CheckOutcome.PENDING to 0, CheckOutcome.WEAK to 0, CheckOutcome.FAIL to 0),
      counts.hidden,
    )
    assertEquals(0, counts.paused)
  }

  @Test
  fun `only an active passing example is shown, per path`() {
    val counts =
      PoolCounts.of(
        listOf(
          example(AudiencePath.USE_AI),
          example(AudiencePath.USE_AI),
          example(AudiencePath.BUILD_WITH_AI),
          example(AudiencePath.BUILD_WITH_AI, status = ExampleStatus.PAUSED),
        )
      )
    assertEquals(2, counts.shown[AudiencePath.USE_AI])
    assertEquals(1, counts.shown[AudiencePath.BUILD_WITH_AI])
  }

  @Test
  fun `an active example the check hides counts under its check, a paused one only as paused`() {
    val counts =
      PoolCounts.of(
        listOf(
          example(outcome = CheckOutcome.WEAK),
          example(outcome = CheckOutcome.WEAK),
          example(outcome = CheckOutcome.FAIL),
          example(outcome = CheckOutcome.PENDING),
          example(status = ExampleStatus.PAUSED, outcome = CheckOutcome.FAIL),
          example(status = ExampleStatus.PAUSED, outcome = CheckOutcome.PASS),
        )
      )
    assertEquals(
      mapOf(CheckOutcome.PENDING to 1, CheckOutcome.WEAK to 2, CheckOutcome.FAIL to 1),
      counts.hidden,
    )
    assertEquals(2, counts.paused)
    assertEquals(0, counts.shown[AudiencePath.USE_AI])
  }
}
