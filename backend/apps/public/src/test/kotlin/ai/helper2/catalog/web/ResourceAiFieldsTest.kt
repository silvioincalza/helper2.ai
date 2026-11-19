package ai.helper2.catalog.web

import ai.helper2.api.AiContentMarking
import ai.helper2.api.generated.model.ResourceDetailDto
import ai.helper2.catalog.ResourceDetail
import ai.helper2.search.MergedResult
import ai.helper2.search.ResultMerger
import ai.helper2.search.contexts
import ai.helper2.search.hit
import ai.helper2.shared.DateFact
import ai.helper2.shared.ResourceType
import ai.helper2.shared.ValueOrigin
import ai.helper2.shared.Verification
import ai.helper2.shared.WebSources
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResourceAiFieldsTest {
  private val notAiWritten: Map<String, String> =
    classed(
      WORDS_OF_THE_SOURCE to
        listOf(
          "card.title",
          "card.url",
          "card.canonicalUrl",
          "card.license",
          "card.tags[]",
          "card.platforms[]",
          "card.sources[].url",
          "card.timeline.published.value",
          "card.timeline.published.alternatives[].value",
          "card.timeline.updated.value",
          "card.timeline.updated.alternatives[].value",
          "card.timeline.eventStart.alternatives[].value",
          "card.timeline.eventEnd.alternatives[].value",
          "card.popularity[].value",
          "card.conflicts[].values[].value",
          "card.foundIn.listTitle",
          "card.foundIn.headingPath",
          "sourceRecords[].url",
          "sourceRecords[].externalId",
          "sourceRecords[].repositoryUrl",
          "foundIn.lists[].listTitle",
          "foundIn.lists[].headingPath",
        ),
      QUOTE_OF_THE_SOURCE to
        listOf(
          "assertions[].evidence[].sourceUrl",
          "assertions[].evidence[].excerpt",
          "relationships[].evidence[].sourceUrl",
          "relationships[].evidence[].excerpt",
          "summary.claims[].evidence[].sourceUrl",
          "summary.claims[].evidence[].excerpt",
        ),
      OUR_WORDS to listOf("card.sources[].sourceName", "card.compatibleWith[].label"),
      FROM_THE_WORD_LIST to listOf("card.useCases[]"),
      CODE_A_MODEL_CAN_CHOOSE to listOf("card.type", "assertions[].field"),
      SET_BY_CODE to
        listOf(
          "card.id",
          "card.descriptionOrigin",
          "card.audience",
          "card.techLevel",
          "card.pricing",
          "card.openSource",
          "card.sources[].sourceId",
          "card.sources[].mode",
          "card.sources[].retrievedAt",
          "card.timeline.published.precision",
          "card.timeline.published.status",
          "card.timeline.published.sourceId",
          "card.timeline.published.alternatives[].sourceId",
          "card.timeline.updated.precision",
          "card.timeline.updated.status",
          "card.timeline.updated.sourceId",
          "card.timeline.updated.alternatives[].sourceId",
          "card.timeline.discovered.value",
          "card.timeline.discovered.alternatives[].value",
          "card.timeline.discovered.precision",
          "card.timeline.discovered.status",
          "card.timeline.discovered.sourceId",
          "card.timeline.discovered.alternatives[].sourceId",
          "card.timeline.eventStart.precision",
          "card.timeline.eventStart.status",
          "card.timeline.eventStart.sourceId",
          "card.timeline.eventStart.alternatives[].sourceId",
          "card.timeline.eventEnd.precision",
          "card.timeline.eventEnd.status",
          "card.timeline.eventEnd.sourceId",
          "card.timeline.eventEnd.alternatives[].sourceId",
          "card.popularity[].metric",
          "card.popularity[].sourceId",
          "card.popularity[].asOf",
          "card.freshness",
          "card.conflicts[].field",
          "card.conflicts[].values[].sourceId",
          "card.compatibleWith[].tool",
          "card.compatibleWith[].basis",
          "card.compatibleWith[].evidence",
          "card.compatibleWith[].sourceId",
          "card.categories[]",
          "card.eventOrigin",
          "assertions[].origin",
          "assertions[].verification",
          "assertions[].sourceId",
          "assertions[].evidence[].id",
          "assertions[].evidence[].retrievedAt",
          "relationships[].targetId",
          "relationships[].verification",
          "relationships[].origin",
          "relationships[].evidence[].id",
          "relationships[].evidence[].retrievedAt",
          "summary.claims[].evidence[].id",
          "summary.claims[].evidence[].retrievedAt",
          "summary.claims[].selfDeclared",
          "summary.model",
          "summary.promptVersion",
          "summary.generatedAt",
          "sourceRecords[].sourceId",
          "sourceRecords[].retrievedAt",
          "sourceRecords[].fingerprint",
          "enrichmentStatus",
          "foundIn.lists[].listId",
          "foundIn.more",
          "aiContent.aiGenerated",
          "aiContent.provider",
          "aiContent.models[]",
          "aiContent.fields[]",
          "aiContent.policy",
        ),
    )

  private val detailFields = leafPaths(ResourceDetailDto::class.java)

  @Test
  fun `every field of the resource detail is classed as written by a model or not`() {
    val unclassed = detailFields.filter {
      it !in ResourceDetailController.AI_WRITTEN_FIELDS && it !in notAiWritten
    }

    assertEquals(
      emptyList(),
      unclassed,
      "add each to ResourceDetailController.AI_WRITTEN_FIELDS (and mark it in the UI) or to " +
        "notAiWritten",
    )
  }

  @Test
  fun `no field is classed twice, and every classed field still exists`() {
    val aiWritten = ResourceDetailController.AI_WRITTEN_FIELDS
    assertEquals(emptyList(), aiWritten.filter { it in notAiWritten })
    assertEquals(emptyList(), aiWritten.filter { it !in detailFields })
    assertEquals(emptyList(), notAiWritten.keys.filter { it !in detailFields })
  }

  @Test
  fun `a resource with no AI-written value has no marker`() {
    val stated =
      detail(
        card = card("What its source says."),
        assertions =
          listOf(
            assertion("license", ValueOrigin.SOURCE_PROVIDED),
            assertion("pricing", ValueOrigin.CURATED),
          ),
      )

    assertNull(marker(stated))
    assertNull(marker(detail()))
  }

  @Test
  fun `a description from a model alone is marked, with no model the card does not keep`() {
    val marker = marker(detail(card = card("A summary.", ValueOrigin.AI_SUMMARY)))!!

    assertEquals(true, marker.aiGenerated)
    assertEquals(listOf("card.description"), marker.fields)
    assertEquals(emptyList(), marker.models)
    assertEquals("nebius", marker.provider)
    assertEquals(AiContentMarking.POLICY, marker.policy)
  }

  @Test
  fun `an event whose days a model read alone is marked`() {
    assertEquals(
      listOf("card.timeline.eventStart.value", "card.location"),
      marker(detail(card = event(WebSources.EVENTS_SCOUT)))!!.fields,
    )
    assertNull(marker(detail(card = event("lu-ma"))))
  }

  @Test
  fun `an assertion from a model alone is marked, with the model of its run`() {
    ValueOrigin.entries
      .filter { it.isFromAModel }
      .forEach { origin ->
        val marker = marker(detail(assertions = listOf(assertion("pricing", origin, "nemotron"))))!!

        assertEquals(listOf("assertions[].value"), marker.fields, origin.name)
        assertEquals(listOf("nemotron"), marker.models, origin.name)
      }
  }

  @Test
  fun `only the assertions from a model are listed, by index, and only their models named`() {
    val mixed =
      detail(
        assertions =
          listOf(
            assertion("license", ValueOrigin.SOURCE_PROVIDED),
            assertion("pricing", ValueOrigin.AI_EXTRACTED, "nemotron"),
            assertion("techLevel", ValueOrigin.CURATED),
            assertion("audience", ValueOrigin.AI_ESTIMATED, "nemotron-next"),
          )
      )

    val marker = marker(mixed)!!
    assertEquals(listOf("assertions[1].value", "assertions[3].value"), marker.fields)
    assertEquals(listOf("nemotron", "nemotron-next"), marker.models)
  }

  @Test
  fun `a relationship alone is marked, its kind and its target`() {
    val marker =
      marker(detail(relationships = listOf(relationship(ValueOrigin.SELF_DECLARED, "nemotron"))))!!

    assertEquals(listOf("relationships[].kind", "relationships[].targetTitle"), marker.fields)
    assertEquals(listOf("nemotron"), marker.models)
  }

  @Test
  fun `a summary alone is marked, with its model`() {
    val marker = marker(detail(summary = summary("nemotron", "It translates documents.")))!!

    assertEquals(listOf("summary.claims[].text"), marker.fields)
    assertEquals(listOf("nemotron"), marker.models)
  }

  @Test
  fun `a summary with no claim marks nothing`() {
    assertNull(marker(detail(summary = summary("nemotron"))))
  }

  @Test
  fun `every kind together is listed in the order of the response, each model once`() {
    val all =
      detail(
        card = card("A summary.", ValueOrigin.AI_SUMMARY),
        assertions = listOf(assertion("pricing", ValueOrigin.AI_EXTRACTED, "nemotron")),
        relationships = listOf(relationship(ValueOrigin.AI_EXTRACTED, "nemotron")),
        summary = summary("nemotron", "It translates documents."),
      )

    val marker = marker(all)!!
    assertEquals(
      listOf(
        "card.description",
        "assertions[].value",
        "relationships[].kind",
        "relationships[].targetTitle",
        "summary.claims[].text",
      ),
      marker.fields,
    )
    assertEquals(listOf("nemotron"), marker.models)
    assertEquals(emptyList(), marker.fields - ResourceDetailController.AI_WRITTEN_FIELDS)
  }

  private fun marker(detail: ResourceDetail) = ResourceDetailController.aiContent(detail, "nebius")

  private fun detail(
    card: MergedResult = card(null),
    assertions: List<ResourceDetail.Assertion> = emptyList(),
    relationships: List<ResourceDetail.Relationship> = emptyList(),
    summary: ResourceDetail.Summary? = null,
  ) =
    ResourceDetail(
      card,
      assertions,
      relationships,
      summary,
      emptyList(),
      "NOT_RUN",
      ResourceDetail.FoundIn.NO_LIST,
    )

  private fun card(
    description: String?,
    origin: ValueOrigin = ValueOrigin.SOURCE_PROVIDED,
  ): MergedResult =
    ResultMerger()
      .merge(
        listOf(
          hit("github", "https://github.com/a/tool", description = description)
            .copy(descriptionOrigin = origin)
        ),
        contexts("github"),
      )
      .single()

  private fun event(source: String): MergedResult =
    ResultMerger()
      .merge(
        listOf(
          hit(source, "https://hack.example/2027", type = ResourceType.EVENT)
            .copy(
              eventStart = DateFact.scheduledDay(LocalDate.parse("2027-03-12"), source),
              location = "Zurich, Switzerland",
            )
        ),
        contexts(source),
      )
      .single()

  private fun assertion(field: String, origin: ValueOrigin, model: String? = null) =
    ResourceDetail.Assertion(
      field,
      "a value",
      origin,
      Verification.VERIFIED,
      null,
      emptyList(),
      model,
    )

  private fun relationship(origin: ValueOrigin, model: String?) =
    ResourceDetail.Relationship(
      "INTEGRATES_WITH",
      null,
      "Slack",
      Verification.UNVERIFIED,
      emptyList(),
      origin,
      model,
    )

  private fun summary(model: String, vararg claims: String) =
    ResourceDetail.Summary(
      claims.map { ResourceDetail.SummaryClaim(it, emptyList(), false) },
      model,
      "v1",
      Instant.EPOCH,
    )

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

  private fun isDto(type: Class<*>) = type.name.startsWith("ai.helper2.api.") && !type.isEnum

  private companion object {
    const val WORDS_OF_THE_SOURCE =
      "the source's own text or numbers, as fetched: titles, links, licences, tags, dates and " +
        "counts; a date another source states comes with that source's id"
    const val QUOTE_OF_THE_SOURCE =
      "a quote of the source and its address: kept only when code finds the quote word for " +
        "word in the text it was read from (EvidenceValidator)"
    const val OUR_WORDS = "our own words: the names of the sources and of the AI tools"
    const val FROM_THE_WORD_LIST =
      "use cases from the fixed EN/IT word list (QueryAnalyzer), never a model's"
    const val CODE_A_MODEL_CAN_CHOOSE =
      "a code from a closed list, not text: a model can choose the type of a repository of an " +
        "awesome list (SpringAiRepoClassifier), and names the field its assertion is about"
    const val SET_BY_CODE =
      "a code, id, number, date or flag set by code from what the sources state; a field an " +
        "enrichment model reads stays in assertions and never changes the card"
  }
}
