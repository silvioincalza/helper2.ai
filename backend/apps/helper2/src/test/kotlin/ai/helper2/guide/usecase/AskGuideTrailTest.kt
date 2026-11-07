package ai.helper2.guide.usecase

import ai.helper2.guide.Candidate
import ai.helper2.guide.GuideModel
import ai.helper2.guide.GuideProposal
import ai.helper2.guide.GuideSearchPlan
import ai.helper2.guide.GuideUnavailable
import ai.helper2.guide.ProposedPick
import ai.helper2.guide.ReasonClaim
import ai.helper2.guide.RemovedSuggestion
import ai.helper2.guide.ResearchTrail
import ai.helper2.guide.TrailCheck
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
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The research trail (ADR 0044): the planner's queries actually run, and each suggestion a check
 * removed, by code.
 */
class AskGuideTrailTest {

  private val executor = Executors.newVirtualThreadPerTaskExecutor()
  private val clock = Clock.fixed(NOW, ZoneOffset.UTC)

  @AfterTest fun shutdown() = executor.shutdownNow().let {}

  private val catalog =
    object : SearchSource {
      override val descriptor = descriptor("hf")

      override fun search(query: SourceQuery): List<SourceHit> =
        listOf(
          hit(
            "hf",
            "https://huggingface.co/acme/whisper-small",
            title = "whisper-small",
            type = ResourceType.MODEL,
            description =
              "Speech recognition model you can self-host, released under the MIT License",
          ),
          hit(
            "hf",
            "https://huggingface.co/acme/promo",
            title = "promo <b>model</b>",
            rank = 1,
            type = ResourceType.MODEL,
            description = "Text to speech voices for podcasts and audiobooks in many languages",
          ),
        )
    }

  /** A web source that reads the planner's web query. */
  private val web =
    object : SearchSource {
      override val descriptor = descriptor("live-web").copy(usesWebQuery = true)

      override fun search(query: SourceQuery): List<SourceHit> = emptyList()
    }

  private val noCache =
    object : SearchCache {
      override fun get(key: String) = null

      override fun put(key: String, value: ai.helper2.search.CachedHits, ttl: Duration) {}
    }

  private fun search(vararg sources: SearchSource) =
    SearchResources(sources.toList(), noCache, ResultObserver { emptyMap() }, executor, clock)

  private class Model(
    val plan: GuideSearchPlan,
    val failPlan: Boolean = false,
    val proposal: (List<Candidate>) -> GuideProposal,
  ) : GuideModel {
    override val provider = "fake"
    override val fastModel = "fast"
    override val reasoningModel = "reasoning"

    override fun plan(request: String, path: AudiencePath?): GuideSearchPlan =
      if (failPlan) throw GuideUnavailable("down") else plan

    override fun choose(request: String, candidates: List<Candidate>, constraints: List<String>) =
      proposal(candidates)

    override fun verifyReasons(request: String, claims: List<ReasonClaim>) = claims.associate {
      it.key to true
    }
  }

  private val whisperPick: (List<Candidate>) -> ProposedPick = { c ->
    ProposedPick(
      c.first { it.result.title == "whisper-small" }.key,
      "Self-hostable.",
      "Speech recognition model you can self-host",
    )
  }

  @Test
  fun `the planned query and the web query a web source read are the trail's queries`() {
    val model =
      Model(
        GuideSearchPlan("speech recognition", webQuery = "best self-hosted speech to text model")
      ) { c ->
        GuideProposal(listOf(whisperPick(c)), null, emptyList())
      }

    val answer =
      AskGuide(search(catalog, web), model, clock).ask("speech to text I can self-host", null)

    assertEquals(
      listOf("speech recognition", "best self-hosted speech to text model"),
      answer.trail.queries,
    )
  }

  @Test
  fun `a web query no source reads is not listed, since it never ran`() {
    val model =
      Model(
        GuideSearchPlan("speech recognition", webQuery = "best self-hosted speech to text model")
      ) { c ->
        GuideProposal(listOf(whisperPick(c)), null, emptyList())
      }

    val answer = AskGuide(search(catalog), model, clock).ask("speech to text I can self-host", null)

    assertEquals(listOf("speech recognition"), answer.trail.queries)
  }

  @Test
  fun `without the planner no query is listed, because the searches ran with the asker's own words`() {
    val model =
      Model(GuideSearchPlan("unused"), failPlan = true) { c ->
        GuideProposal(listOf(whisperPick(c)), null, emptyList())
      }

    val answer = AskGuide(search(catalog), model, clock).ask("speech to text I can self-host", null)

    assertEquals(emptyList(), answer.trail.queries)
  }

  @Test
  fun `a blank planned query is not listed, since the search then ran with the asker's own text`() {
    val model =
      Model(GuideSearchPlan("  ")) { c -> GuideProposal(listOf(whisperPick(c)), null, emptyList()) }

    val answer = AskGuide(search(catalog), model, clock).ask("speech to text I can self-host", null)

    assertEquals(emptyList(), answer.trail.queries)
  }

  @Test
  fun `a removed suggestion is listed by its source title and the check's code, never by the model's reason`() {
    val model =
      Model(GuideSearchPlan("speech recognition")) { c ->
        val promo = c.first { it.result.title.startsWith("promo") }.key
        GuideProposal(
          listOf(
            whisperPick(c),
            ProposedPick(
              promo,
              "SECRET-REASON the model wrote",
              "a quote the model made up about this voice model",
            ),
            ProposedPick(
              promo,
              "",
              "Text to speech voices for podcasts and audiobooks in many languages",
            ),
          ),
          null,
          emptyList(),
        )
      }

    val answer = AskGuide(search(catalog), model, clock).ask("speech to text I can self-host", null)

    assertEquals(
      listOf(
        RemovedSuggestion("promo <b>model</b>", TrailCheck.QUOTE_NOT_FOUND),
        RemovedSuggestion("promo <b>model</b>", TrailCheck.REASON_NOT_SUPPORTED),
      ),
      answer.trail.removed,
    )
    assertFalse(
      "SECRET-REASON" in answer.toString(),
      "a removed reason is kept nowhere in the answer",
    )
  }

  @Test
  fun `a candidate rejected once and then kept is a pick, not a removed suggestion`() {
    val model =
      Model(GuideSearchPlan("speech recognition")) { c ->
        val whisper = whisperPick(c)
        GuideProposal(
          listOf(whisper.copy(quote = "not in the text at all, invented by the model"), whisper),
          null,
          emptyList(),
        )
      }

    val answer = AskGuide(search(catalog), model, clock).ask("speech to text I can self-host", null)

    assertEquals(1, answer.recommendations.count { it.aiReasoned })
    assertEquals(emptyList(), answer.trail.removed)
  }

  @Test
  fun `the trail keeps at most 3 queries of 200 characters, and caps the removed list`() {
    val trail =
      ResearchTrail.of(
        listOf("one", " one ", "", "x".repeat(250), "three", "four"),
        List(20) { RemovedSuggestion("t".repeat(300), TrailCheck.QUOTE_NOT_FOUND) },
      )

    assertEquals(listOf("one", "x".repeat(200), "three"), trail.queries)
    assertTrue(trail.queries.all { it.length <= ResearchTrail.MAX_QUERY_CHARS })
    assertEquals(ResearchTrail.MAX_REMOVED, trail.removed.size)
    assertTrue(trail.removed.all { it.title.length == ResearchTrail.MAX_TITLE_CHARS })
  }
}
