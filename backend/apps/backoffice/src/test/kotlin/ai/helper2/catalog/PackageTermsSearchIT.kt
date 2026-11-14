package ai.helper2.catalog

import ai.helper2.TestcontainersConfiguration
import ai.helper2.catalog.internal.JdbcCatalogSearch
import ai.helper2.catalog.usecase.IndexResources
import ai.helper2.search.NOW
import ai.helper2.search.PopularityKind
import ai.helper2.search.PopularityMetric
import ai.helper2.search.QueryAnalyzer
import ai.helper2.search.ResultMerger
import ai.helper2.search.ServedFrom
import ai.helper2.search.SourceContext
import ai.helper2.search.SourceHit
import ai.helper2.search.descriptor
import ai.helper2.search.hit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class PackageTermsSearchIT {

  @Autowired lateinit var indexResources: IndexResources
  @Autowired lateinit var catalogSearch: JdbcCatalogSearch
  @Autowired lateinit var jdbc: JdbcClient

  private val origins = setOf(DataOrigin.INDEXED, DataOrigin.OBSERVED)

  private fun repo(
    name: String,
    stars: Long,
    description: String? = null,
    packageTerms: String? = null,
  ): SourceHit =
    hit("github-sdks", "https://github.com/$name", title = name, description = description)
      .copy(
        popularity = listOf(PopularityMetric(PopularityKind.GITHUB_STARS, stars, "github", NOW)),
        facts =
          if (packageTerms == null) emptyMap()
          else mapOf("package" to mapOf("terms" to packageTerms)),
      )

  private fun index(vararg hits: SourceHit) {
    indexResources.index(
      ResultMerger()
        .merge(
          hits.toList(),
          mapOf("github-sdks" to SourceContext(descriptor("github-sdks"), ServedFrom.LIVE)),
        ),
      "github-sdks",
    )
  }

  private fun ranked(text: String, prefix: String): List<String> {
    val q = QueryAnalyzer().analyze(text)
    return catalogSearch
      .search(text, q.requiredVariants, q.expandedTerms, q.synonyms, null, origins, 50, null)
      .map { it.title() }
      .filter { it.startsWith(prefix) }
  }

  @Test
  fun `java sdk for claude finds the repo whose package is com anthropic anthropic-java`() {
    index(
      repo(
        "pkgit-one/anthropic-sdk-java",
        391,
        packageTerms = "kotlin java com anthropic maven library package sdk claude",
      ),
      repo("pkgit-one/java-sdk-kit", 20_000, description = "Java SDK toolkit"),
      repo("pkgit-one/other-sdk-java", 391),
    )

    val titles = ranked("java sdk for claude", "pkgit-one/")

    assertEquals("pkgit-one/anthropic-sdk-java", titles.first(), "$titles")
    assertEquals(
      "anthropic claude com java kotlin library maven package sdk",
      jdbc
        .sql("SELECT package_terms FROM resource WHERE title = 'pkgit-one/anthropic-sdk-java'")
        .query(String::class.java)
        .single(),
    )
    assertNull(
      jdbc
        .sql("SELECT package_terms FROM resource WHERE title = 'pkgit-one/java-sdk-kit'")
        .query(String::class.java)
        .optional()
        .orElse(null)
    )
  }

  @Test
  fun `rust llm library finds the repo whose crate and language say rust`() {
    index(
      repo(
        "pkgit-two/forge",
        900,
        description = "Fast LLM inference engine",
        packageTerms = "rust forge core cargo crate crates library package",
      ),
      repo("pkgit-two/llm-forge-notes", 15_000, description = "Notes on LLM serving"),
      repo("pkgit-two/forge-plain", 900, description = "Fast LLM inference engine"),
    )

    val titles = ranked("rust llm library", "pkgit-two/")

    assertEquals("pkgit-two/forge", titles.first(), "$titles")
  }
}
