package ai.helper2.guide.internal

import ai.helper2.guide.Candidate
import ai.helper2.guide.GuideAnswerCache
import ai.helper2.guide.GuideIntent
import ai.helper2.guide.GuideModel
import ai.helper2.guide.GuideProposal
import ai.helper2.guide.GuideSearchPlan
import ai.helper2.guide.Need
import ai.helper2.guide.ProposedPick
import ai.helper2.guide.ReasonClaim
import ai.helper2.guide.ReasonVerdict
import ai.helper2.guide.usecase.AskGuide
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
import ai.helper2.shared.ResourceType
import ai.helper2.testing.InMemorySharedCache
import ai.helper2.testing.MovableClock
import java.time.Duration
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import tools.jackson.databind.json.JsonMapper

class StoredAnswerPrivacyTest {

  private val clock = MovableClock(NOW)
  private val executor = Executors.newVirtualThreadPerTaskExecutor()
  private val shared = InMemorySharedCache("guide-answer")

  @AfterTest fun shutdown() = executor.shutdownNow().let {}

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
            published = "2026-01-02T00:00:00Z",
            description =
              "Speech recognition model you can self-host, released under the MIT License",
          )
        )
    }

  private val noCache =
    object : SearchCache {
      override fun get(key: String) = null

      override fun put(key: String, value: CachedHits, ttl: Duration) {}
    }

  private fun model(plan: GuideSearchPlan) =
    object : GuideModel {
      override val provider = "fake"
      override val fastModel = "fast"
      override val reasoningModel = "reasoning"

      override fun plan(request: String, path: AudiencePath?) = plan

      override fun choose(request: Need, candidates: List<Candidate>, constraints: List<String>) =
        GuideProposal(
          candidates.map {
            ProposedPick(it.key, "Self-hostable.", "Speech recognition model you can self-host")
          }
        )

      override fun verifyReasons(request: Need, claims: List<ReasonClaim>) = claims.associate {
        it.key to ReasonVerdict(true)
      }
    }

  private fun storedAfter(plan: GuideSearchPlan, question: String, clarification: String?): String {
    val search =
      SearchResources(listOf(source), noCache, ResultObserver { emptyMap() }, executor, clock)
    val cache =
      GuideAnswerCache(
        Duration.ofHours(24),
        clock,
        SharedGuideAnswerStore(shared, JsonMapper.builder().build(), "b1"),
      )
    AskGuide(search, model(plan), clock, cache = cache)
      .ask(question, null, clarification = clarification)
    assertEquals(1, shared.values.size, "the answer must be stored for this test to mean anything")
    return shared.values.values.single().lowercase()
  }

  private fun assertHoldsNeither(stored: String, vararg words: String) = words.forEach {
    assertFalse(it.lowercase() in stored, "the store holds \"$it\"")
  }

  @Test
  fun `a planned answer keeps the planner's terms, never the question or the reply to a question back`() {
    val question = "speech to text that I can run on my own box"
    val reply = "only ones my team could self-host"

    val stored = storedAfter(GuideSearchPlan("speech recognition"), question, reply)

    assertHoldsNeither(stored, question, reply)
  }

  @Test
  fun `when the planner wrote no query, the question it fell back to is not stored`() {
    val question = "speech to text that I can run on my own box"
    val reply = "only ones my team could self-host"

    val stored = storedAfter(GuideSearchPlan(""), question, reply)

    assertHoldsNeither(stored, question, reply, "my own box", "my team could")
  }

  @Test
  fun `a dated answer stores no search words taken from the question`() {
    val question = "when did whisper-small first come out for my laptop"

    val stored =
      storedAfter(
        GuideSearchPlan("", intent = GuideIntent.WHEN, subjects = listOf("whisper-small")),
        question,
        null,
      )

    assertHoldsNeither(stored, question, "for my laptop")
  }
}
