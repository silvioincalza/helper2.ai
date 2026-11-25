package ai.searchavo.site.pages.usecase

import ai.searchavo.catalog.ResourceDetail
import ai.searchavo.search.ResultMerger
import ai.searchavo.search.contexts
import ai.searchavo.search.hit
import ai.searchavo.shared.DateFact
import ai.searchavo.shared.ResourceType
import ai.searchavo.shared.WebSources
import ai.searchavo.site.pages.Related
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RenderPageViewTest {

  private fun view(
    source: String,
    foundIn: ResourceDetail.FoundIn = ResourceDetail.FoundIn.NO_LIST,
  ) =
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
        foundIn,
        null,
      )
    )

  @Test
  fun `the lists a resource was found in are on the server page, as links to their pages`() {
    val list = UUID.randomUUID()
    val found =
      ResourceDetail.FoundIn(
        listOf(ResourceDetail.ListEntry(list, "acme/awesome-events", "Hackathons")),
        3,
      )

    assertEquals(listOf(Related(list, "acme/awesome-events")), view("ai-deadlines", found).foundIn)
    assertEquals(emptyList(), view("ai-deadlines").foundIn)
  }

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
