package ai.helper2.guide.usecase

import ai.helper2.guide.Candidate
import ai.helper2.guide.GuideJudge
import ai.helper2.guide.GuideModel
import ai.helper2.guide.GuideProposal
import ai.helper2.guide.GuideSearchPlan
import ai.helper2.guide.GuideStatus
import ai.helper2.guide.GuideValidator
import ai.helper2.guide.Need
import ai.helper2.guide.ProposedPick
import ai.helper2.guide.ReasonCheck
import ai.helper2.guide.ReasonClaim
import ai.helper2.guide.ReasonVerdict
import ai.helper2.guide.TrailCheck
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
import kotlin.test.assertTrue

class AskGuideFiveForThreeTest {

  private val executor = Executors.newVirtualThreadPerTaskExecutor()
  private val clock = Clock.fixed(ai.helper2.search.NOW, ZoneOffset.UTC)

  @AfterTest fun shutdown() = executor.shutdownNow().let {}

  private val names = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot")

  private fun textOf(name: String) =
    "Speech recognition model $name transcribes meetings you can self-host"

  private val source =
    object : SearchSource {
      override val descriptor = descriptor("hf")

      override fun search(query: SourceQuery): List<SourceHit> = names.mapIndexed { i, name ->
        hit(
          "hf",
          "https://huggingface.co/acme/$name",
          title = name,
          rank = i,
          type = ResourceType.MODEL,
          description = textOf(name),
        )
      }
    }

  private val noCache =
    object : SearchCache {
      override fun get(key: String) = null

      override fun put(key: String, value: ai.helper2.search.CachedHits, ttl: Duration) {}
    }

  private val search =
    SearchResources(listOf(source), noCache, ResultObserver { emptyMap() }, executor, clock)

  private class FiveModel(
    val picked: List<String>,
    val misquoted: Set<String> = emptySet(),
    val verdict: (String) -> ReasonVerdict,
  ) : GuideModel {
    var claimsSeen: List<ReasonClaim> = emptyList()
    var verifyCalls = 0
    private var titles: Map<String, String> = emptyMap()

    override val provider = "fake"
    override val fastModel = "fast"
    override val reasoningModel = "reasoning"

    override fun plan(request: String, path: AudiencePath?) = GuideSearchPlan("speech recognition")

    override fun choose(
      request: Need,
      candidates: List<Candidate>,
      constraints: List<String>,
    ): GuideProposal {
      val byTitle = candidates.associateBy { it.result.title }
      titles = candidates.associate { it.key to it.result.title }
      return GuideProposal(
        picked.map { name ->
          val quote =
            if (name in misquoted) "A quote that the text of $name never says" else textOf(name)
          ProposedPick(byTitle.getValue(name).key, "A $name reason.", quote)
        }
      )
    }

    override fun verifyReasons(request: Need, claims: List<ReasonClaim>) =
      claims
        .associate { it.key to verdict(titles.getValue(it.key)) }
        .also {
          claimsSeen = claims
          verifyCalls++
        }

    private fun textOf(name: String) =
      "Speech recognition model $name transcribes meetings you can self-host"
  }

  private val need = "speech to text I can self-host"

  private val fitting = ReasonVerdict(true)

  @Test
  fun `the model may propose five picks, and every one is checked in one call`() {
    val model = FiveModel(names.take(5)) { fitting }

    AskGuide(search, model, clock).ask(need, null)

    assertEquals(GuideValidator.MAX_PROPOSED, model.claimsSeen.size)
    assertEquals(1, model.verifyCalls)
  }

  @Test
  fun `a pick the check finds off the need is replaced by the next one the model proposed`() {
    val model =
      FiveModel(names.take(5)) { title ->
        if (title == "alpha") ReasonVerdict(true, fitsNeed = false) else fitting
      }

    val answer = AskGuide(search, model, clock).ask(need, null)

    assertEquals(
      listOf("bravo", "charlie", "delta"),
      answer.recommendations.map { it.result.title },
    )
    assertTrue(answer.recommendations.all { it.aiReasoned })
    assertEquals(TrailCheck.OFF_NEED, answer.trail.removed.single().check)
  }

  @Test
  fun `the user sees at most three picks, in the model's order, and spares count as no rejection`() {
    val model = FiveModel(names.take(5)) { fitting }

    val answer = AskGuide(search, model, clock).ask(need, null)

    assertEquals(
      listOf("alpha", "bravo", "charlie"),
      answer.recommendations.map { it.result.title },
    )
    assertEquals(0, answer.rejected)
    assertEquals(GuideStatus.OK, answer.status)
    assertTrue(answer.trail.removed.isEmpty())
  }

  @Test
  fun `a spare the user would not see is no rejection, even when it is off the need`() {
    val model =
      FiveModel(names.take(5)) { title ->
        if (title == "echo") ReasonVerdict(true, fitsNeed = false) else fitting
      }

    val answer = AskGuide(search, model, clock).ask(need, null)

    assertEquals(
      listOf("alpha", "bravo", "charlie"),
      answer.recommendations.map { it.result.title },
    )
    assertEquals(0, answer.rejected)
    assertEquals(GuideStatus.OK, answer.status)
    assertTrue(answer.trail.removed.isEmpty())
  }

  @Test
  fun `a spare whose quote is not in its text is no rejection either`() {
    val model = FiveModel(names.take(5), misquoted = setOf("delta")) { fitting }

    val answer = AskGuide(search, model, clock).ask(need, null)

    assertEquals(
      listOf("alpha", "bravo", "charlie"),
      answer.recommendations.map { it.result.title },
    )
    assertEquals(0, answer.rejected)
    assertEquals(GuideStatus.OK, answer.status)
    assertTrue(answer.trail.removed.isEmpty())
  }

  @Test
  fun `a misquoted pick before the third is still a rejection`() {
    val model = FiveModel(names.take(5), misquoted = setOf("alpha")) { fitting }

    val answer = AskGuide(search, model, clock).ask(need, null)

    assertEquals(
      listOf("bravo", "charlie", "delta"),
      answer.recommendations.map { it.result.title },
    )
    assertEquals(1, answer.rejected)
    assertEquals(GuideStatus.PARTIAL, answer.status)
  }

  @Test
  fun `a pick whose reason the check removes keeps its place, as it does today`() {
    val model =
      FiveModel(names.take(5)) { title ->
        if (title == "alpha") ReasonVerdict(false) else fitting
      }

    val answer = AskGuide(search, model, clock).ask(need, null)

    assertEquals(
      listOf("alpha", "bravo", "charlie"),
      answer.recommendations.map { it.result.title },
    )
    assertEquals(ReasonCheck.REMOVED, answer.recommendations.first().reasonCheck)
  }

  @Test
  fun `the judge gets every claim the reason check saw, with its verdict`() {
    val model =
      FiveModel(names.take(5)) { title ->
        if (title == "alpha") ReasonVerdict(false) else fitting
      }
    val judged = mutableListOf<Pair<List<ReasonClaim>, Map<String, ReasonCheck>>>()

    AskGuide(
        search,
        model,
        clock,
        judge = GuideJudge { _, claims, checks -> judged += claims to checks },
      )
      .ask(need, null)

    assertEquals(1, judged.size)
    assertEquals(model.claimsSeen, judged.single().first)
    assertEquals(
      ReasonCheck.REMOVED,
      judged.single().second.getValue(model.claimsSeen.first().key),
    )
  }

  @Test
  fun `a judge that cannot start leaves the answer as it is`() {
    val model = FiveModel(names.take(5)) { fitting }

    val answer =
      AskGuide(search, model, clock, judge = GuideJudge { _, _, _ -> error("no thread") })
        .ask(need, null)

    assertEquals(GuideValidator.MAX_PICKS, answer.recommendations.size)
    assertTrue(answer.recommendations.all { it.reasonCheck == ReasonCheck.SUPPORTED })
  }

  @Test
  fun `a sixth proposed pick is over the limit`() {
    val model = FiveModel(names) { fitting }

    val answer = AskGuide(search, model, clock).ask(need, null)

    assertEquals(GuideValidator.MAX_PROPOSED, model.claimsSeen.size)
    assertEquals(GuideValidator.MAX_PICKS, answer.recommendations.size)
  }
}
