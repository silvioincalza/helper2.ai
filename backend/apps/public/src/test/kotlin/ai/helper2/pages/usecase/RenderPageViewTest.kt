package ai.helper2.pages.usecase

import ai.helper2.catalog.ResourceDetail
import ai.helper2.search.ResultMerger
import ai.helper2.search.contexts
import ai.helper2.search.hit
import ai.helper2.shared.DateFact
import ai.helper2.shared.ResourceType
import ai.helper2.shared.WebSources
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A server page shows only event facts it can show without the AI label (ADR 0018, 0021). */
class RenderPageViewTest {

  private fun view(source: String) =
    RenderPage.view(
      ResourceDetail(
        ResultMerger()
          .merge(
            listOf(
              hit(source, "https://hack.example/2027", type = ResourceType.EVENT)
                .copy(
                  eventStart = DateFact.scheduledDay(LocalDate.parse("2027-03-12"), source),
                  eventEnd = DateFact.scheduledDay(LocalDate.parse("2027-03-14"), source),
                  location = "Zurich, Switzerland",
                )
            ),
            contexts(source),
          )
          .single(),
        emptyList(),
        emptyList(),
        null,
        emptyList(),
        null,
      )
    )

  @Test
  fun `days and place an AI model read stay off the server page`() {
    val v = view(WebSources.EVENTS_SCOUT)
    assertNull(v.eventStart)
    assertNull(v.eventEnd)
    assertNull(v.location)
  }

  @Test
  fun `days and place a source states are on the server page`() {
    val v = view("ai-deadlines")
    assertEquals("2027-03-12T00:00:00Z", v.eventStart.toString())
    assertEquals("Zurich, Switzerland", v.location)
  }
}
