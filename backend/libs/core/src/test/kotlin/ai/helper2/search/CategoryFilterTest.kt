package ai.helper2.search

import ai.helper2.shared.Category
import ai.helper2.shared.CategoryCatalog
import ai.helper2.shared.CategoryMatcher
import ai.helper2.shared.CategoryWords
import ai.helper2.shared.ResourceType
import ai.helper2.shared.ValueOrigin
import kotlin.test.Test
import kotlin.test.assertEquals

class CategoryFilterTest {

  private val matcher =
    CategoryMatcher(
      CategoryCatalog(
        listOf(
          Category(
            "coding",
            "Coding",
            "Coding",
            "developers",
            CategoryWords.of(listOf("coding assistant")),
          ),
          Category(
            "self-hosted",
            "Self-hosted",
            "Self-hosted",
            "run-it-yourself",
            CategoryWords.of(listOf("self hosted")),
          ),
        )
      )
    )
  private val merger = ResultMerger(QueryAnalyzer(), matcher)
  private val contexts =
    mapOf(
      "live-web" to SourceContext(descriptor("live-web"), ServedFrom.LIVE),
      "helper2-index" to
        SourceContext(
          descriptor("helper2-index", ai.helper2.shared.SourceMode.INDEXED),
          ServedFrom.LIVE,
        ),
    )

  private val liveCoding =
    hit(
      "live-web",
      "https://acme.dev",
      title = "Acme, the AI coding assistant",
      type = ResourceType.PRODUCT,
    )
  private val liveSelfHosted =
    hit(
      "live-web",
      "https://box.example",
      description = "A self-hosted chat",
      type = ResourceType.PRODUCT,
    )
  private val indexedBoth =
    hit("helper2-index", "https://both.example", title = "Both")
      .copy(categories = setOf("coding", "self-hosted"))
  private val plain = hit("live-web", "https://plain.example", title = "Plain")

  private val merged =
    merger.merge(listOf(liveCoding, liveSelfHosted, indexedBoth, plain), contexts)

  private fun titles(filters: SearchFilters) = Filtering.apply(merged, filters).map { it.title }

  @Test
  fun `a live web result gets its categories when it is merged`() {
    assertEquals(setOf("coding"), merged.single { it.title.startsWith("Acme") }.categories)
  }

  @Test
  fun `a catalog result keeps the categories the catalog stored, in the configured order`() {
    assertEquals(
      listOf("coding", "self-hosted"),
      merged.single { it.title == "Both" }.categories.toList(),
    )
  }

  @Test
  fun `an AI-written description does not give a category`() {
    val aiWritten =
      hit("live-web", "https://ai.example", title = "Ai", description = "a coding assistant")
        .copy(descriptionOrigin = ValueOrigin.AI_SUMMARY)
    assertEquals(emptySet(), merger.merge(listOf(aiWritten), contexts).single().categories)
  }

  @Test
  fun `two categories are ORed`() {
    assertEquals(
      setOf("Acme, the AI coding assistant", "box.example", "Both"),
      titles(SearchFilters(categories = setOf("coding", "self-hosted"))).toSet(),
    )
  }

  @Test
  fun `the category filter is ANDed with the other filters`() {
    assertEquals(
      listOf("Both"),
      titles(SearchFilters(types = setOf(ResourceType.LIBRARY), categories = setOf("coding"))),
    )
  }

  @Test
  fun `the no-category bucket keeps the results without one`() {
    assertEquals(listOf("Plain"), titles(SearchFilters(categories = setOf(UNKNOWN))))
  }

  @Test
  fun `the category facet counts every category and the results without one, ignoring its own filter`() {
    val facet = Facets.compute(merged, SearchFilters(categories = setOf("coding")))["category"]!!
    assertEquals(
      listOf(FacetCount("coding", 2), FacetCount("self-hosted", 2), FacetCount(UNKNOWN, 1)),
      facet,
    )
  }
}
