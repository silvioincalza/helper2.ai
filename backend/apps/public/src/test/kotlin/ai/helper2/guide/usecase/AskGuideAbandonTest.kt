package ai.helper2.guide.usecase

import ai.helper2.guide.Candidate
import ai.helper2.guide.ClientQuota
import ai.helper2.guide.ComparisonProposal
import ai.helper2.guide.ExplanationProposal
import ai.helper2.guide.GuideAnswer
import ai.helper2.guide.GuideAnswerCache
import ai.helper2.guide.GuideModel
import ai.helper2.guide.GuideProgress
import ai.helper2.guide.GuideProposal
import ai.helper2.guide.GuideSearchPlan
import ai.helper2.guide.GuideUnavailable
import ai.helper2.guide.MemoryAnswerStore
import ai.helper2.guide.Need
import ai.helper2.guide.ProposedPick
import ai.helper2.guide.ReasonCheck
import ai.helper2.guide.ReasonClaim
import ai.helper2.guide.ReasonVerdict
import ai.helper2.platform.quota.QuotaKeys
import ai.helper2.search.CachedHits
import ai.helper2.search.NOW
import ai.helper2.search.ResultObserver
import ai.helper2.search.SearchCache
import ai.helper2.search.SearchSource
import ai.helper2.search.SourceHit
import ai.helper2.search.SourceQuery
import ai.helper2.search.descriptor
import ai.helper2.search.hit
import ai.helper2.search.usecase.SearchResources
import ai.helper2.shared.AudiencePath
import ai.helper2.shared.Presence
import ai.helper2.shared.ResourceType
import ai.helper2.shared.SingleFlight
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
    meters.counter("helper2.guide.questions", "outcome", outcome).count()

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
        .counter("helper2.guide.quota.give_back", "reason", "abandoned", "outcome", "given_back")
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
    assertEquals(0.0, meters.find("helper2.guide.quota.give_back").counters().sumOf { it.count() })
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
