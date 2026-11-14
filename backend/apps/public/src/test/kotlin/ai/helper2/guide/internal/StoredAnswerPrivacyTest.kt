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
import kotlin.test.assertTrue
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

  private fun assertHolds(stored: String, words: String) =
    assertTrue(words.lowercase() in stored, "the store does not hold \"$words\"")

  @Test
  fun `a short question the planner searches as it stands is stored whole, as the planned search`() {
    val question = "dictation software"

    val stored = storedAfter(GuideSearchPlan("dictation software"), question, null)

    assertHolds(stored, question)
  }

  @Test
  fun `a short question that is only a name is stored whole, as the subject read in it`() {
    val question = "Frobnicator"

    val stored =
      storedAfter(
        GuideSearchPlan("", intent = GuideIntent.ALTERNATIVES, subjects = listOf("Frobnicator")),
        question,
        null,
      )

    assertHolds(stored, question)
  }

  @Test
  fun `a person's name or a phone number the guide reads as a subject is stored, the rest of the question is not`() {
    val name = "Maria Rossi"
    val phone = "+41 79 555 01 23"
    val question = "alternatives to the dictation tool of $name, call $phone to ask my cousin"

    val stored =
      storedAfter(
        GuideSearchPlan(
          "dictation tool",
          intent = GuideIntent.ALTERNATIVES,
          subjects = listOf(name, phone),
        ),
        question,
        null,
      )

    assertHolds(stored, name)
    assertHolds(stored, phone)
    assertHoldsNeither(stored, question, "to ask my cousin", "alternatives to the")
  }

  @Test
  fun `a person's name and a phone number the planner leaves out of its search are not stored`() {
    val name = "Maria Rossi"
    val phone = "+41 79 555 01 23"
    val question = "a dictation tool for $name, call $phone when it is found"

    val stored = storedAfter(GuideSearchPlan("dictation tool"), question, null)

    assertHoldsNeither(stored, question, name, phone, "79 555")
  }

  @Test
  fun `an e-mail address is never stored, even when the planner reads it as a subject or a search`() {
    val address = "maria.rossi@example.org"
    val question = "alternatives to the dictation tool of $address"

    val stored =
      storedAfter(
        GuideSearchPlan(address, intent = GuideIntent.ALTERNATIVES, subjects = listOf(address)),
        question,
        null,
      )

    assertHoldsNeither(stored, question, address, "maria.rossi")
  }
}
