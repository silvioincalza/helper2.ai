package ai.helper2.guide.usecase

import ai.helper2.guide.Candidate
import ai.helper2.guide.GuideAnswerCache
import ai.helper2.guide.GuideModel
import ai.helper2.guide.GuideProposal
import ai.helper2.guide.GuideSearchPlan
import ai.helper2.guide.MemoryAnswerStore
import ai.helper2.guide.ProposedPick
import ai.helper2.guide.ReasonClaim
import ai.helper2.search.CachedHits
import ai.helper2.search.ResultObserver
import ai.helper2.search.SearchCache
import ai.helper2.search.SearchSource
import ai.helper2.search.SourceHit
import ai.helper2.search.SourceQuery
import ai.helper2.search.descriptor
import ai.helper2.search.hit
import ai.helper2.search.usecase.SearchResources
import ai.helper2.shared.AudiencePath
import ai.helper2.shared.ResourceType
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The guide asked from a results page with a category filter picks only in those categories. */
class AskGuideCategoryTest {

  private val executor = Executors.newVirtualThreadPerTaskExecutor()
  private val clock = Clock.fixed(ai.helper2.search.NOW, ZoneOffset.UTC)

  @AfterTest fun shutdown() = executor.shutdownNow().let {}

  private val source =
    object : SearchSource {
      override val descriptor = descriptor("index")

      override fun search(query: SourceQuery): List<SourceHit> =
        listOf(
          hit(
              "index",
              "https://acme.dev",
              title = "Acme code review bot",
              type = ResourceType.PRODUCT,
              description = "Reviews pull requests",
            )
            .copy(categories = setOf("coding")),
          hit(
            "index",
            "https://notes.example",
            title = "Notes review app",
            rank = 1,
            type = ResourceType.PRODUCT,
            description = "Reviews your meeting notes",
          ),
        )
    }

  private val noCache =
    object : SearchCache {
      override fun get(key: String) = null

      override fun put(key: String, value: CachedHits, ttl: Duration) {}
    }

  private val search =
    SearchResources(listOf(source), noCache, ResultObserver { emptyMap() }, executor, clock)

  private val need = "a tool that reviews my work"

  @Test
  fun `without a category filter both kinds of result are candidates`() {
    val answer = AskGuide(search, null, clock).ask(need, null)

    assertEquals(2, answer.search.results.size)
  }

  @Test
  fun `with a category filter only results in it are candidates`() {
    val answer = AskGuide(search, null, clock).ask(need, null, categories = setOf("coding"))

    assertEquals(listOf("Acme code review bot"), answer.search.results.map { it.title })
    assertEquals(listOf("Acme code review bot"), answer.recommendations.map { it.result.title })
  }

  /** Picks the first candidate, quoting its description. */
  private val model =
    object : GuideModel {
      override val provider = "fake"
      override val fastModel = "fast"
      override val reasoningModel = "reasoning"

      override fun plan(request: String, path: AudiencePath?) = GuideSearchPlan("review")

      override fun choose(request: String, candidates: List<Candidate>, constraints: List<String>) =
        GuideProposal(
          listOf(
            ProposedPick(
              candidates.first().key,
              "It reviews.",
              candidates.first().result.description!!,
              emptyList(),
            )
          ),
          null,
          emptyList(),
        )

      override fun verifyReasons(request: String, claims: List<ReasonClaim>) = claims.associate {
        it.key to true
      }
    }

  @Test
  fun `the category filter is part of the cached answer's key`() {
    val guide =
      AskGuide(
        search,
        model,
        clock,
        cache = GuideAnswerCache(Duration.ofHours(24), clock, MemoryAnswerStore()),
      )

    assertEquals(2, guide.ask(need, null).search.results.size)
    val filtered = guide.ask(need, null, categories = setOf("coding"))

    assertEquals(listOf("Acme code review bot"), filtered.search.results.map { it.title })
  }
}
