package ai.searchavo.guide.usecase

import ai.searchavo.guide.Candidate
import ai.searchavo.guide.GuideAnswerCache
import ai.searchavo.guide.GuideModel
import ai.searchavo.guide.GuideProposal
import ai.searchavo.guide.GuideSearchPlan
import ai.searchavo.guide.MemoryAnswerStore
import ai.searchavo.guide.Need
import ai.searchavo.guide.ProposedPick
import ai.searchavo.guide.ReasonClaim
import ai.searchavo.guide.ReasonVerdict
import ai.searchavo.search.CachedHits
import ai.searchavo.search.ResultObserver
import ai.searchavo.search.SearchCache
import ai.searchavo.search.SearchSource
import ai.searchavo.search.SourceHit
import ai.searchavo.search.SourceQuery
import ai.searchavo.search.descriptor
import ai.searchavo.search.hit
import ai.searchavo.search.usecase.SearchResources
import ai.searchavo.shared.AudiencePath
import ai.searchavo.shared.ResourceType
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AskGuideCategoryTest {

  private val executor = Executors.newVirtualThreadPerTaskExecutor()
  private val clock = Clock.fixed(ai.searchavo.search.NOW, ZoneOffset.UTC)

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

  private val model =
    object : GuideModel {
      override val provider = "fake"
      override val fastModel = "fast"
      override val reasoningModel = "reasoning"

      override fun plan(request: String, path: AudiencePath?) = GuideSearchPlan("review")

      override fun choose(request: Need, candidates: List<Candidate>, constraints: List<String>) =
        GuideProposal(
          listOf(
            ProposedPick(
              candidates.first().key,
              "It reviews.",
              candidates.first().result.description!!,
              emptyList(),
            )
          )
        )

      override fun verifyReasons(request: Need, claims: List<ReasonClaim>) = claims.associate {
        it.key to ReasonVerdict(true)
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
