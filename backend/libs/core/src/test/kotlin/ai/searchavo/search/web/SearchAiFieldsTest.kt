package ai.searchavo.search.web

import ai.searchavo.api.AiContentMarking
import ai.searchavo.api.common.model.SearchResponseDto
import ai.searchavo.search.MergedResult
import ai.searchavo.search.ResultMerger
import ai.searchavo.search.contexts
import ai.searchavo.search.hit
import ai.searchavo.shared.DateFact
import ai.searchavo.shared.EventFormat
import ai.searchavo.shared.EventKind
import ai.searchavo.shared.ResourceType
import ai.searchavo.shared.ValueOrigin
import ai.searchavo.shared.WebSources
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SearchAiFieldsTest {
  private val notAiWritten: Map<String, String> =
    classed(
      WORDS_OF_THE_SOURCE to
        listOf(
          "results[].title",
          "results[].url",
          "results[].canonicalUrl",
          "results[].license",
          "results[].tags[]",
          "results[].platforms[]",
          "results[].sources[].url",
          "results[].timeline.published.value",
          "results[].timeline.published.alternatives[].value",
          "results[].timeline.updated.value",
          "results[].timeline.updated.alternatives[].value",
          "results[].timeline.eventStart.alternatives[].value",
          "results[].timeline.eventEnd.alternatives[].value",
          "results[].popularity[].value",
          "results[].conflicts[].values[].value",
          "results[].foundIn.listTitle",
          "results[].foundIn.headingPath",
        ),
      WORDS_OF_THE_ASKER to listOf("query"),
      OUR_WORDS to
        listOf(
          "notices[].message",
          "coverage.note",
          "sources[].name",
          "sources[].message",
          "results[].sources[].sourceName",
          "results[].compatibleWith[].label",
        ),
      FROM_THE_WORD_LIST to
        listOf(
          "interpretation.expandedTerms[]",
          "interpretation.detectedUseCases[]",
          "results[].useCases[]",
        ),
      NEVER_SET_BY_SEARCH to listOf("notices[].subject", "notices[].query"),
      CODE_A_MODEL_CAN_CHOOSE to listOf("results[].type"),
      SET_BY_CODE to
        listOf(
          "notices[].code",
          "notices[].retryAt",
          "path",
          "sort",
          "executedAt",
          "coverage.partial",
          "coverage.sourcesConfigured",
          "coverage.sourcesQueried",
          "coverage.sourcesSucceeded",
          "sources[].sourceId",
          "sources[].mode",
          "sources[].outcome",
          "sources[].servedFrom",
          "sources[].resultCount",
          "sources[].latencyMs",
          "sources[].checkedAt",
          "sources[].cachedAt",
          "sources[].skipReason",
          "results[].id",
          "results[].descriptionOrigin",
          "results[].audience",
          "results[].techLevel",
          "results[].pricing",
          "results[].openSource",
          "results[].sources[].sourceId",
          "results[].sources[].mode",
          "results[].sources[].retrievedAt",
          "results[].timeline.published.precision",
          "results[].timeline.published.status",
          "results[].timeline.published.sourceId",
          "results[].timeline.published.alternatives[].sourceId",
          "results[].timeline.updated.precision",
          "results[].timeline.updated.status",
          "results[].timeline.updated.sourceId",
          "results[].timeline.updated.alternatives[].sourceId",
          "results[].timeline.discovered.value",
          "results[].timeline.discovered.alternatives[].value",
          "results[].timeline.discovered.precision",
          "results[].timeline.discovered.status",
          "results[].timeline.discovered.sourceId",
          "results[].timeline.discovered.alternatives[].sourceId",
          "results[].timeline.eventStart.precision",
          "results[].timeline.eventStart.status",
          "results[].timeline.eventStart.sourceId",
          "results[].timeline.eventStart.alternatives[].sourceId",
          "results[].timeline.eventEnd.precision",
          "results[].timeline.eventEnd.status",
          "results[].timeline.eventEnd.sourceId",
          "results[].timeline.eventEnd.alternatives[].sourceId",
          "results[].popularity[].metric",
          "results[].popularity[].sourceId",
          "results[].popularity[].asOf",
          "results[].freshness",
          "results[].conflicts[].field",
          "results[].conflicts[].values[].sourceId",
          "results[].compatibleWith[].tool",
          "results[].compatibleWith[].basis",
          "results[].compatibleWith[].evidence",
          "results[].compatibleWith[].sourceId",
          "results[].categories[]",
          "results[].eventOrigin",
          "facets.type[].value",
          "facets.type[].count",
          "facets.source[].value",
          "facets.source[].count",
          "facets.license[].value",
          "facets.license[].count",
          "facets.openSource[].value",
          "facets.openSource[].count",
          "facets.pricing[].value",
          "facets.pricing[].count",
          "facets.platform[].value",
          "facets.platform[].count",
          "facets.useCase[].value",
          "facets.useCase[].count",
          "facets.techLevel[].value",
          "facets.techLevel[].count",
          "facets.audience[].value",
          "facets.audience[].count",
          "facets.category[].value",
          "facets.category[].count",
          "total",
          "page",
          "size",
          "webSearches.limit",
          "webSearches.remaining",
          "aiContent.aiGenerated",
          "aiContent.provider",
          "aiContent.models[]",
          "aiContent.fields[]",
          "aiContent.policy",
        ),
    )

  private val responseFields = leafPaths(SearchResponseDto::class.java)

  @Test
  fun `every field of the search response is classed as written by a model or not`() {
    val unclassed = responseFields.filter {
      it !in SearchController.AI_WRITTEN_FIELDS && it !in notAiWritten
    }

    assertEquals(
      emptyList(),
      unclassed,
      "add each to SearchController.AI_WRITTEN_FIELDS (and mark it in the UI) or to notAiWritten",
    )
  }

  @Test
  fun `no field is classed twice, and every classed field still exists`() {
    assertEquals(emptyList(), SearchController.AI_WRITTEN_FIELDS.filter { it in notAiWritten })
    assertEquals(emptyList(), SearchController.AI_WRITTEN_FIELDS.filter { it !in responseFields })
    assertEquals(emptyList(), notAiWritten.keys.filter { it !in responseFields })
  }

  @Test
  fun `a response with no AI-written value has no marker`() {
    assertNull(marker(null, card("plain", description = "What its source says.")))
    assertNull(marker(null))
  }

  @Test
  fun `the translation of the query alone is marked, with the model that wrote it`() {
    val marker = marker("vector database", card("plain"))!!

    assertEquals(true, marker.aiGenerated)
    assertEquals(listOf("interpretation.translatedQuery"), marker.fields)
    assertEquals(listOf("nano"), marker.models)
    assertEquals("nebius", marker.provider)
    assertEquals(AiContentMarking.POLICY, marker.policy)
  }

  @Test
  fun `a description from a model alone is marked, and names no model the card does not keep`() {
    ValueOrigin.entries
      .filter { it.isFromAModel }
      .forEach { origin ->
        val marker = marker(null, card("summed", "A summary.", origin))!!

        assertEquals(listOf("results[].description"), marker.fields, origin.name)
        assertEquals(emptyList(), marker.models, origin.name)
      }
  }

  @Test
  fun `a description people wrote is not marked, whatever its origin`() {
    ValueOrigin.entries
      .filterNot { it.isFromAModel }
      .forEach { origin ->
        assertNull(marker(null, card("stated", "Its own words.", origin)), origin.name)
      }
  }

  @Test
  fun `an origin from a model with no description marks nothing`() {
    assertNull(marker(null, card("empty", null, ValueOrigin.AI_SUMMARY)))
  }

  @Test
  fun `an event whose days a model read alone is marked, each value it holds`() {
    val full =
      event(WebSources.EVENTS_SCOUT) {
        it.copy(
          eventEnd = DateFact.scheduledDay(LocalDate.parse("2027-03-03"), it.sourceId),
          eventKind = EventKind.CONFERENCE,
          eventFormat = EventFormat.IN_PERSON,
          location = "Zurich, Switzerland",
        )
      }

    assertEquals(
      listOf(
        "results[].timeline.eventStart.value",
        "results[].timeline.eventEnd.value",
        "results[].eventKind",
        "results[].eventFormat",
        "results[].location",
      ),
      marker(null, full)!!.fields,
    )
    assertEquals(
      listOf("results[].timeline.eventStart.value"),
      marker(null, event(WebSources.EVENTS_SCOUT))!!.fields,
    )
  }

  @Test
  fun `an event whose days a source states is not marked`() {
    assertNull(marker(null, event("lu-ma")))
  }

  @Test
  fun `a value only some results hold is listed by index`() {
    val marker =
      marker(
        "vector database",
        card("plain"),
        card("summed", "A summary.", ValueOrigin.AI_SUMMARY),
        event(WebSources.EVENTS_SCOUT),
      )!!

    assertEquals(
      listOf(
        "interpretation.translatedQuery",
        "results[1].description",
        "results[2].timeline.eventStart.value",
      ),
      marker.fields,
    )
  }

  @Test
  fun `every listed path is a classed AI-written field`() {
    val marker =
      marker(
        "vector database",
        card("summed", "A summary.", ValueOrigin.AI_SUMMARY),
        event(WebSources.EVENTS_SCOUT),
      )!!

    assertEquals(
      emptyList(),
      marker.fields.map { it.replace(Regex("\\[\\d+]"), "[]") } -
        SearchController.AI_WRITTEN_FIELDS,
    )
  }

  private fun marker(translation: String?, vararg results: MergedResult) =
    SearchController.aiContent(translation, results.toList(), "nebius", "nano")

  private fun card(
    name: String,
    description: String? = null,
    origin: ValueOrigin = ValueOrigin.SOURCE_PROVIDED,
  ): MergedResult =
    ResultMerger()
      .merge(
        listOf(
          hit("github", "https://github.com/a/$name", description = description)
            .copy(descriptionOrigin = origin)
        ),
        contexts("github"),
      )
      .single()

  private fun event(
    source: String,
    more: (ai.searchavo.search.SourceHit) -> ai.searchavo.search.SourceHit = { it },
  ): MergedResult =
    ResultMerger()
      .merge(
        listOf(
          more(
            hit(source, "https://hack.example/$source", type = ResourceType.EVENT)
              .copy(eventStart = DateFact.scheduledDay(LocalDate.parse("2027-03-01"), source))
          )
        ),
        contexts(source),
      )
      .single()

  private fun classed(vararg groups: Pair<String, List<String>>): Map<String, String> =
    groups.flatMap { (why, paths) -> paths.map { it to why } }.toMap()

  private fun leafPaths(type: Class<*>, prefix: String = ""): List<String> =
    type.declaredFields
      .filter { !Modifier.isStatic(it.modifiers) }
      .flatMap { field -> paths(field, prefix + field.name) }

  private fun paths(field: Field, path: String): List<String> {
    val generic = field.genericType
    if (generic is ParameterizedType && List::class.java.isAssignableFrom(field.type)) {
      val element = generic.actualTypeArguments.single() as Class<*>
      return if (isDto(element)) leafPaths(element, "$path[].") else listOf("$path[]")
    }
    return if (isDto(field.type)) leafPaths(field.type, "$path.") else listOf(path)
  }

  private fun isDto(type: Class<*>) = type.name.startsWith("ai.searchavo.api.") && !type.isEnum

  private companion object {
    const val WORDS_OF_THE_SOURCE =
      "the source's own text or numbers, as fetched: titles, links, licences, tags, dates and " +
        "counts; a date another source states comes with that source's id"
    const val WORDS_OF_THE_ASKER = "the asker's own words"
    const val OUR_WORDS =
      "our own words; QUERY_EXPANDED lists word-list terms only, never the translation " +
        "(AnalyzedQuery.lexiconTerms)"
    const val FROM_THE_WORD_LIST =
      "terms and use cases from the fixed EN/IT word list (QueryAnalyzer), never a model's"
    const val NEVER_SET_BY_SEARCH =
      "the guide's fields of a notice; a search notice has a code and a message only"
    const val CODE_A_MODEL_CAN_CHOOSE =
      "a code from a closed list, not text: for a repository of an awesome list a model can " +
        "choose the type among the allowed ones (SpringAiRepoClassifier)"
    const val SET_BY_CODE =
      "a code, id, number, date or flag set by code from what the sources state; a field an " +
        "enrichment model reads stays in ResourceDetail.assertions and never changes the card"
  }
}
