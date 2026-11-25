package ai.searchavo.guide.usecase

import ai.searchavo.guide.Candidate
import ai.searchavo.guide.ClientQuota
import ai.searchavo.guide.ComparisonProposal
import ai.searchavo.guide.ExplanationProposal
import ai.searchavo.guide.GuideAnswer
import ai.searchavo.guide.GuideAnswerCache
import ai.searchavo.guide.GuideModel
import ai.searchavo.guide.GuideProgress
import ai.searchavo.guide.GuideProposal
import ai.searchavo.guide.GuideSearchPlan
import ai.searchavo.guide.GuideUnavailable
import ai.searchavo.guide.MemoryAnswerStore
import ai.searchavo.guide.Need
import ai.searchavo.guide.ProposedPick
import ai.searchavo.guide.ReasonCheck
import ai.searchavo.guide.ReasonClaim
import ai.searchavo.guide.ReasonVerdict
import ai.searchavo.platform.quota.QuotaKeys
import ai.searchavo.search.CachedHits
import ai.searchavo.search.NOW
import ai.searchavo.search.ResultObserver
import ai.searchavo.search.SearchCache
import ai.searchavo.search.SearchSource
import ai.searchavo.search.SourceHit
import ai.searchavo.search.SourceQuery
import ai.searchavo.search.descriptor
import ai.searchavo.search.hit
import ai.searchavo.search.usecase.SearchResources
import ai.searchavo.shared.AudiencePath
import ai.searchavo.shared.Presence
import ai.searchavo.shared.ResourceType
import ai.searchavo.shared.SingleFlight
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AskGuideAbandonTest {

  private val executor = Executors.newVirtualThreadPerTaskExecutor()
  private val clock = Clock.fixed(NOW, ZoneOffset.UTC)
  private val meters = SimpleMeterRegistry()

  @AfterTest
  fun shutdown() {
    executor.shutdownNow()
  }

  private val source =
    object : SearchSource {
      override val descriptor = descriptor("hf")

      override fun search(query: SourceQuery): List<SourceHit> =
        listOf(
          hit(
            "hf",
            "https://huggingface.co/acme/whisper-small",
            title = "whisper-small",
            type = ResourceType.MODEL,
            description = "Speech recognition model you can self-host",
          )
        )
    }

  private val noCache =
    object : SearchCache {
      override fun get(key: String) = null

      override fun put(key: String, value: CachedHits, ttl: Duration) {}
    }

  private val search =
    SearchResources(listOf(source), noCache, ResultObserver { emptyMap() }, executor, clock)

  private class SlowReasonCheck : GuideModel {
    override val provider = "fake"
    override val fastModel = "fast"
    override val reasoningModel = "reasoning"
    val checking = CountDownLatch(1)
    val release = CountDownLatch(1)
    val chosen = AtomicInteger()

    override fun plan(request: String, path: AudiencePath?) = GuideSearchPlan("speech recognition")

    override fun choose(
      request: Need,
      candidates: List<Candidate>,
      constraints: List<String>,
    ): GuideProposal {
      chosen.incrementAndGet()
      return GuideProposal(
        listOf(
          ProposedPick(
            candidates.single().key,
            "Self-hostable.",
            "Speech recognition model you can self-host",
          )
        )
      )
    }

    override fun verifyReasons(
      request: Need,
      claims: List<ReasonClaim>,
    ): Map<String, ReasonVerdict> {
      checking.countDown()
      try {
        release.await()
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        throw GuideUnavailable("the reason check was interrupted", GuideUnavailable.CANCELLED)
      }
      return claims.associate { it.key to ReasonVerdict(true) }
    }

    override fun compare(request: Need, subjects: List<Candidate>) = ComparisonProposal(emptyList())

    override fun explain(request: Need, subject: String, candidates: List<Candidate>) =
      ExplanationProposal(emptyList())
  }

  private val need = "speech to text I can self-host"
  private val asker = "10.0.0.1"
  private val quota = ClientQuota(3, clock, QuotaKeys("test-secret", clock))
  private val model = SlowReasonCheck()
  private val store = MemoryAnswerStore()

  private fun guide(stopsWhenNobodyWaits: Boolean) =
    AskGuide(
      search,
      model,
      clock,
      cache = GuideAnswerCache(Duration.ofHours(24), clock, store),
      meters = meters,
      quota = quota,
      stopsWhenNobodyWaits = stopsWhenNobodyWaits,
    )

  private val guide = guide(stopsWhenNobodyWaits = true)
  private val guideThatFinishes = guide(stopsWhenNobodyWaits = false)

  private fun ask(
    client: String,
    presence: Presence,
    guide: AskGuide = this.guide,
  ): CompletableFuture<GuideAnswer> =
    CompletableFuture.supplyAsync(
      { guide.ask(need, null, GuideProgress.NONE, client, presence = presence) },
      executor,
    )

  private fun CompletableFuture<GuideAnswer>.failure(): Throwable =
    assertFailsWith<Exception> { get(5, TimeUnit.SECONDS) }.cause!!

  private fun questions(outcome: String) =
    meters.counter("searchavo.guide.questions", "outcome", outcome).count()

  @Test
  fun `a question whose asker leaves stops, has no answer and is given back`() {
    val presence = Presence()
    val asked = ask(asker, presence)
    assertTrue(model.checking.await(5, TimeUnit.SECONDS))
    assertEquals(2, quota.left(asker))

    presence.leave()

    assertIs<SingleFlight.Abandoned>(asked.failure())
    assertEquals(3, quota.left(asker))
    assertEquals(1.0, questions("abandoned"))
    assertEquals(0.0, questions("timeout"))
    assertEquals(
      1.0,
      meters
        .counter("searchavo.guide.quota.give_back", "reason", "abandoned", "outcome", "given_back")
        .count(),
    )
  }

  @Test
  fun `an abandoned question is not cached, so asking again runs it afresh`() {
    val presence = Presence()
    val asked = ask(asker, presence)
    assertTrue(model.checking.await(5, TimeUnit.SECONDS))
    presence.leave()
    asked.failure()
    model.release.countDown()

    val again = ask(asker, Presence()).get(5, TimeUnit.SECONDS)

    assertEquals(2, model.chosen.get())
    assertEquals(ReasonCheck.SUPPORTED, again.recommendations.single().reasonCheck)
  }

  @Test
  fun `a question goes on when its first asker leaves and another still waits for it`() {
    val first = Presence()
    val asked = ask(asker, first)
    assertTrue(model.checking.await(5, TimeUnit.SECONDS))
    val other = ask("10.0.0.2", Presence())
    Thread.sleep(300)

    first.leave()
    model.release.countDown()

    val answer = other.get(5, TimeUnit.SECONDS)
    assertEquals(ReasonCheck.SUPPORTED, answer.recommendations.single().reasonCheck)
    assertEquals(1, model.chosen.get())
    assertEquals(0.0, questions("abandoned"))
    asked.get(5, TimeUnit.SECONDS)
  }

  @Test
  fun `where questions do not stop, a question whose asker left finishes, is cached and counts as any answer`() {
    val presence = Presence()
    val asked = ask(asker, presence, guideThatFinishes)
    assertTrue(model.checking.await(5, TimeUnit.SECONDS))

    presence.leave()
    model.release.countDown()

    val answer = asked.get(5, TimeUnit.SECONDS)
    assertEquals(ReasonCheck.SUPPORTED, answer.recommendations.single().reasonCheck)
    assertEquals(1, store.puts)
    assertEquals(2, quota.left(asker))
    assertEquals(1.0, questions("answered"))
    assertEquals(0.0, questions("abandoned"))
    assertEquals(
      0.0,
      meters.find("searchavo.guide.quota.give_back").counters().sumOf { it.count() },
    )
  }

  @Test
  fun `where questions do not stop, asking again after leaving is a free cache hit`() {
    val presence = Presence()
    val asked = ask(asker, presence, guideThatFinishes)
    assertTrue(model.checking.await(5, TimeUnit.SECONDS))
    presence.leave()
    model.release.countDown()
    asked.get(5, TimeUnit.SECONDS)

    ask(asker, Presence(), guideThatFinishes).get(5, TimeUnit.SECONDS)

    assertEquals(1, model.chosen.get())
    assertEquals(1.0, questions("cached"))
    assertEquals(2, quota.left(asker))
  }

  @Test
  fun `where questions do not stop, an asker who waits for a question in progress and leaves still gets its answer`() {
    val asked = ask(asker, Presence(), guideThatFinishes)
    assertTrue(model.checking.await(5, TimeUnit.SECONDS))
    val waiting = Presence()
    val other = ask("10.0.0.2", waiting, guideThatFinishes)

    waiting.leave()
    model.release.countDown()

    assertEquals(
      ReasonCheck.SUPPORTED,
      other.get(5, TimeUnit.SECONDS).recommendations.single().reasonCheck,
    )
    asked.get(5, TimeUnit.SECONDS)
    assertEquals(0.0, questions("abandoned"))
  }
}
