package ai.helper2.guide.usecase

import ai.helper2.guide.Candidate
import ai.helper2.guide.Drops
import ai.helper2.guide.GuideAnswerCache
import ai.helper2.guide.GuideIntent
import ai.helper2.guide.GuideModel
import ai.helper2.guide.GuideProposal
import ai.helper2.guide.GuideSearchPlan
import ai.helper2.guide.MemoryAnswerStore
import ai.helper2.guide.MemoryQuestionIndex
import ai.helper2.guide.Need
import ai.helper2.guide.ProposedPick
import ai.helper2.guide.ReasonClaim
import ai.helper2.guide.ReasonVerdict
import ai.helper2.guide.SimilarQuestions
import ai.helper2.guide.TableEmbedder
import ai.helper2.platform.quota.QuotaCounter
import ai.helper2.platform.quota.QuotaCounter.Counter
import ai.helper2.platform.quota.QuotaCounter.Result
import ai.helper2.platform.quota.QuotaCounter.Verdict
import ai.helper2.platform.quota.QuotaKeys
import ai.helper2.search.CachedHits
import ai.helper2.search.ListedNames
import ai.helper2.search.NOW
import ai.helper2.search.NoticeCode
import ai.helper2.search.ResultObserver
import ai.helper2.search.SearchCache
import ai.helper2.search.SearchSource
import ai.helper2.search.SourceHit
import ai.helper2.search.SourceQuery
import ai.helper2.search.WebSearchQuota
import ai.helper2.search.descriptor
import ai.helper2.search.hit
import ai.helper2.search.usecase.SearchResources
import ai.helper2.shared.AudiencePath
import ai.helper2.shared.ResourceType
import ai.helper2.testing.MovableClock
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AskGuideSimilarTest {

  private val executor = Executors.newVirtualThreadPerTaskExecutor()
  private val clock = MovableClock(NOW)

  @AfterTest fun shutdown() = executor.shutdownNow().let {}

  private val webCalls = AtomicInteger()
  private val web =
    object : SearchSource {
      override val descriptor = descriptor("live-web").copy(metered = true)

      override fun search(query: SourceQuery): List<SourceHit> {
        webCalls.incrementAndGet()
        return listOf(
          hit(
            "live-web",
            "https://example.org/pdf-chat",
            title = "pdf-chat",
            type = ResourceType.PRODUCT,
            description = "Chat with your PDF files, free and open source",
          )
        )
      }
    }

  private val counts = HashMap<Counter, Long>()
  private val counter =
    object : QuotaCounter {
      override fun take(own: Counter?, ownLimit: Int, global: Counter?, globalLimit: Long): Result {
        val used = own?.let { counts[it] ?: 0 } ?: 0
        if (own != null && used >= ownLimit) return Result(Verdict.LIMIT_REACHED, used)
        own?.let { counts[it] = used + 1 }
        return Result(Verdict.TAKEN, used + 1)
      }

      override fun add(counter: Counter, amount: Long) {}

      override fun giveBack(counter: Counter) {}

      override fun used(counter: Counter) = counts[counter] ?: 0
    }

  private val noCache =
    object : SearchCache {
      override fun get(key: String): CachedHits? = null

      override fun put(key: String, value: CachedHits, ttl: Duration) {}
    }

  private val search =
    SearchResources(
      listOf(web),
      noCache,
      ResultObserver { emptyMap() },
      executor,
      clock,
      quota = WebSearchQuota(counter, QuotaKeys("test-secret", clock)),
    )

  private class Model : GuideModel {
    var plans = 0
    var chooses = 0
    var picksNothing = false
    override val provider = "fake"
    override val fastModel = "fast"
    override val reasoningModel = "reasoning"

    override fun plan(request: String, path: AudiencePath?): GuideSearchPlan {
      plans++
      return GuideSearchPlan(
        "pdf chat",
        listOfNotNull(
          "free".takeIf { "free" in request.lowercase() },
          "paid".takeIf { "paid" in request.lowercase() },
        ),
      )
    }

    override fun choose(
      request: Need,
      candidates: List<Candidate>,
      constraints: List<String>,
    ): GuideProposal {
      chooses++
      if (picksNothing) return GuideProposal(emptyList())
      return GuideProposal(
        candidates.map { ProposedPick(it.key, "Chats with PDFs.", it.result.description!!) }
      )
    }

    override fun verifyReasons(request: Need, claims: List<ReasonClaim>) = claims.associate {
      it.key to ReasonVerdict(true)
    }
  }

  private val original = "a free tool to chat with pdfs"
  private val paraphrase = "free tool for chatting with pdf files"
  private val vectors =
    mapOf(
      original to floatArrayOf(1f, 0f, 0f),
      paraphrase to floatArrayOf(0.96f, 0.28f, 0f),
      "a paid tool to chat with pdfs" to floatArrayOf(0.99f, 0.141f, 0f),
      "a free tool to chat with spreadsheets" to floatArrayOf(0.6f, 0.8f, 0f),
    )
  private val model = Model()
  private val index = MemoryQuestionIndex()
  private val embedder = TableEmbedder(vectors)
  private val meters = SimpleMeterRegistry()

  private val listed = ListedNames()

  private fun guide(
    similar: SimilarQuestions? = SimilarQuestions(0.95, "embed", embedder, index, clock)
  ) =
    AskGuide(
      search,
      model,
      clock,
      cache = GuideAnswerCache(Duration.ofHours(24), clock, MemoryAnswerStore()),
      meters = meters,
      similar = similar,
      shadowLibraries = listed.libraries,
    )

  private fun cache(result: String) =
    meters.counter("helper2.guide.cache", "result", result).count()

  private fun questions(outcome: String) =
    meters.counter("helper2.guide.questions", "outcome", outcome).count()

  @Test
  fun `a paraphrase read the same way gets the cached answer after the plan alone`() {
    val guide = guide()
    val first = guide.ask(original, null)

    val reused = guide.ask(paraphrase, null)

    assertEquals(2, model.plans, "the paraphrase is planned, to know how it is read")
    assertEquals(1, model.chooses, "but not answered again")
    assertEquals(1, webCalls.get(), "and not searched again")
    assertEquals(
      first.copy(
        webSearches = null,
        searchQuery = "",
        trail = first.trail.withoutQueries(),
        search = reused.search,
      ),
      reused.copy(webSearches = null),
    )
    assertEquals(first.search.results, reused.search.results)
    assertEquals(
      first.generatedAt,
      reused.generatedAt,
      "the page states the age of the answer it shows",
    )
    assertEquals(listOf(1.0, 1.0, 0.0), listOf(cache("miss"), cache("similar"), cache("exact")))
    assertEquals(listOf(1.0, 1.0), listOf(questions("answered"), questions("cached")))
  }

  @Test
  fun `a similar answer that cites a name listed since then is not reused`() {
    val guide = guide()
    guide.ask(original, null)

    listed.add("example")
    guide.ask(paraphrase, null)

    assertEquals(2, model.chooses, "the paraphrase is answered again")
    assertEquals(0.0, cache("similar"))
  }

  @Test
  fun `a reused answer carries no queries, now or as an exact hit later, because they came from another asker's words`() {
    val guide = guide()
    val first = guide.ask(original, null)

    val reused = guide.ask(paraphrase, null)
    val again = guide.ask(paraphrase, null)

    assertEquals(
      listOf("pdf chat"),
      first.trail.queries,
      "the original asker sees the queries of their own question",
    )
    assertEquals(emptyList(), reused.trail.queries)
    assertEquals("", reused.searchQuery, "nor the planned search query")
    assertEquals(1.0, cache("exact"), "the second paraphrase is an exact hit")
    assertEquals(emptyList(), again.trail.queries)
    assertEquals("", again.searchQuery)
  }

  @Test
  fun `a reused answer keeps the name not found but not the planned search shown instead, which came from another asker's words`() {
    val alternatives =
      object : GuideModel {
        override val provider = "fake"
        override val fastModel = "fast"
        override val reasoningModel = "reasoning"

        override fun plan(request: String, path: AudiencePath?) =
          GuideSearchPlan(
            "pdf chat",
            intent = GuideIntent.ALTERNATIVES,
            subjects = listOf("Frobnicator"),
          )

        override fun choose(
          request: Need,
          candidates: List<Candidate>,
          constraints: List<String>,
        ) =
          GuideProposal(
            candidates.map { ProposedPick(it.key, "Chats with PDFs.", it.result.description!!) }
          )

        override fun verifyReasons(request: Need, claims: List<ReasonClaim>) = claims.associate {
          it.key to ReasonVerdict(true)
        }
      }
    val embedder =
      TableEmbedder(
        mapOf(
          "alternatives to frobnicator" to floatArrayOf(1f, 0f, 0f),
          "frobnicator alternatives" to floatArrayOf(0.96f, 0.28f, 0f),
        )
      )
    val guide =
      AskGuide(
        search,
        alternatives,
        clock,
        cache = GuideAnswerCache(Duration.ofHours(24), clock, MemoryAnswerStore()),
        meters = meters,
        similar = SimilarQuestions(0.95, "embed", embedder, MemoryQuestionIndex(), clock),
      )
    val first =
      guide.ask("alternatives to Frobnicator", null).notices.single {
        it.code == NoticeCode.GUIDE_SUBJECT_NOT_FOUND
      }

    val reused =
      guide.ask("Frobnicator alternatives", null).notices.single {
        it.code == NoticeCode.GUIDE_SUBJECT_NOT_FOUND
      }

    assertEquals(1.0, cache("similar"))
    assertEquals(
      "pdf chat",
      first.query,
      "the original asker sees the search of their own question",
    )
    assertEquals(
      "Frobnicator",
      reused.subject,
      "the same name: it is part of how both questions were read",
    )
    assertNull(reused.query)
  }

  @Test
  fun `a reused answer drops the other asker's "also searched for" terms`() {
    val alternatives =
      object : GuideModel {
        override val provider = "fake"
        override val fastModel = "fast"
        override val reasoningModel = "reasoning"

        override fun plan(request: String, path: AudiencePath?) =
          GuideSearchPlan(
            "riassunto pdf",
            intent = GuideIntent.ALTERNATIVES,
            subjects = listOf("Frobnicator"),
          )

        override fun choose(
          request: Need,
          candidates: List<Candidate>,
          constraints: List<String>,
        ) =
          GuideProposal(
            candidates.map { ProposedPick(it.key, "Chats with PDFs.", it.result.description!!) }
          )

        override fun verifyReasons(request: Need, claims: List<ReasonClaim>) = claims.associate {
          it.key to ReasonVerdict(true)
        }
      }
    val embedder =
      TableEmbedder(
        mapOf(
          "alternatives to frobnicator" to floatArrayOf(1f, 0f, 0f),
          "frobnicator alternatives" to floatArrayOf(0.96f, 0.28f, 0f),
        )
      )
    val guide =
      AskGuide(
        search,
        alternatives,
        clock,
        cache = GuideAnswerCache(Duration.ofHours(24), clock, MemoryAnswerStore()),
        meters = meters,
        similar = SimilarQuestions(0.95, "embed", embedder, MemoryQuestionIndex(), clock),
      )
    val first = guide.ask("alternatives to Frobnicator", null)
    assertTrue(
      first.search.notices.any { it.code == NoticeCode.QUERY_EXPANDED },
      "the planned Italian search gets its English terms",
    )

    val reused = guide.ask("Frobnicator alternatives", null)

    assertEquals(1.0, cache("similar"))
    assertTrue(
      reused.search.notices.none { it.code == NoticeCode.QUERY_EXPANDED },
      "the terms came from the other asker's search",
    )
  }

  @Test
  fun `a reused answer stays AI-marked and spends none of the asker's web searches`() {
    val guide = guide()
    guide.ask(original, null, client = "203.0.113.7")

    val reused = guide.ask(paraphrase, null, client = "198.51.100.1")

    assertTrue(
      reused.modelUsed && reused.recommendations.all { it.aiReasoned },
      "the AI marking of ADR 0033 comes with the answer",
    )
    assertEquals(3, reused.webSearches!!.remaining, "a guest's three web searches, untouched")
    assertEquals(1, webCalls.get())
  }

  @Test
  fun `a question read with another constraint, or too far away, is answered fresh`() {
    val guide = guide()
    guide.ask(original, null)

    guide.ask("a paid tool to chat with pdfs", null)
    guide.ask("a free tool to chat with spreadsheets", null)

    assertEquals(3, model.chooses)
    assertEquals(listOf(3.0, 0.0), listOf(cache("miss"), cache("similar")))
  }

  @Test
  fun `the reused answer is kept under the paraphrase's own key, but never indexed`() {
    val guide = guide()
    guide.ask(original, null)
    guide.ask(paraphrase, null)

    guide.ask(paraphrase, null)

    assertEquals(2, model.plans, "the third question is an exact hit: no plan call")
    assertEquals(1.0, cache("exact"))
    assertEquals(
      1,
      index.size,
      "a reused answer is not a new neighbour: no chain of near twins drifting away",
    )
  }

  @Test
  fun `an answer with no pick is kept for the same question only, never for a similar one`() {
    model.picksNothing = true
    val guide = guide()
    guide.ask(original, null)

    guide.ask(original, null)

    assertEquals(1, model.plans, "the same question is an exact hit")
    assertEquals(0, index.size)
  }

  @Test
  fun `a reused answer expires when the original does`() {
    val guide = guide()
    guide.ask(original, null)
    clock.now = NOW.plus(Duration.ofHours(23))
    guide.ask(paraphrase, null)

    clock.now = NOW.plus(Duration.ofHours(24))
    guide.ask(paraphrase, null)

    assertEquals(2, model.chooses, "past the original's 24 h the paraphrase is answered fresh")
  }

  @Test
  fun `a question with a clarification or a removed part of its reading is never looked up`() {
    val guide = guide()
    guide.ask(original, null)

    guide.ask(paraphrase, null, clarification = "")
    guide.ask(paraphrase, null, drops = Drops(window = true))

    assertEquals(3, model.chooses)
    assertEquals(listOf(original), embedder.asked)
  }

  @Test
  fun `subjects are read in the user's order, so a reversed direction is never reused, whatever order the planner gives`() {
    val sameOrder =
      object : GuideModel by model {
        override fun plan(request: String, path: AudiencePath?) =
          GuideSearchPlan("language bindings", subjects = listOf("python", "rust")).also {
            model.plans++
          }
      }
    val embedder =
      TableEmbedder(
        mapOf(
          "python bindings for rust" to floatArrayOf(1f, 0f),
          "rust bindings for python" to floatArrayOf(0.99f, 0.141f),
        )
      )
    val guide =
      AskGuide(
        search,
        sameOrder,
        clock,
        cache = GuideAnswerCache(Duration.ofHours(24), clock, MemoryAnswerStore()),
        meters = meters,
        similar = SimilarQuestions(0.95, "embed", embedder, index, clock),
      )
    guide.ask("python bindings for rust", null)

    guide.ask("rust bindings for python", null)

    assertEquals(2, model.chooses)
    assertEquals(0.0, cache("similar"))
  }

  @Test
  fun `without a threshold no question is embedded`() {
    val guide = guide(similar = null)
    guide.ask(original, null)
    guide.ask(paraphrase, null)

    assertEquals(2, model.chooses)
    assertTrue(embedder.asked.isEmpty())
    assertEquals(listOf(2.0, 0.0), listOf(cache("miss"), cache("similar")))
  }

  @Test
  fun `a question that cannot be embedded is answered fresh and not indexed`() {
    val guide = guide()

    guide.ask("a question with no recorded vector", null)

    assertEquals(1, model.chooses)
    assertEquals(0, index.size)
    assertEquals(1.0, cache("miss"))
  }

  private class SpelledAsAsked : GuideModel {
    val read = mutableListOf<String>()
    var chooses = 0
    override val provider = "fake"
    override val fastModel = "fast"
    override val reasoningModel = "reasoning"

    private val name = Regex("(?i)frobnicator[- ]pro")
    private val requirement = Regex("(?i)self[- ]hosted")

    override fun plan(request: String, path: AudiencePath?): GuideSearchPlan {
      read += request
      return GuideSearchPlan(
        "pdf chat",
        constraints = listOfNotNull(requirement.find(request)?.value),
        intent = GuideIntent.ALTERNATIVES,
        subjects = listOfNotNull(name.find(request)?.value),
      )
    }

    override fun plan(request: String, path: AudiencePath?, clarification: String?) =
      plan(request, path).also { read += clarification.orEmpty() }

    override fun choose(
      request: Need,
      candidates: List<Candidate>,
      constraints: List<String>,
    ): GuideProposal {
      chooses++
      read += listOfNotNull(request.request, request.answer)
      return GuideProposal(
        candidates.map { ProposedPick(it.key, "Chats with PDFs.", it.result.description!!) }
      )
    }

    override fun verifyReasons(
      request: Need,
      claims: List<ReasonClaim>,
    ): Map<String, ReasonVerdict> {
      read += listOfNotNull(request.request, request.answer)
      return claims.associate { it.key to ReasonVerdict(true) }
    }
  }

  private fun guideWith(model: GuideModel, vectors: Map<String, FloatArray> = emptyMap()) =
    AskGuide(
      search,
      model,
      clock,
      cache = GuideAnswerCache(Duration.ofHours(24), clock, MemoryAnswerStore()),
      meters = meters,
      similar =
        SimilarQuestions(0.95, "embed", TableEmbedder(vectors), MemoryQuestionIndex(), clock),
    )

  @Test
  fun `a reused answer names its subjects and constraints as the second asker wrote them, with nothing of the first asker's spelling`() {
    val spelled = SpelledAsAsked()
    val guide =
      guideWith(
        spelled,
        mapOf(
          "self-hosted alternatives to frobnicator-pro" to floatArrayOf(1f, 0f, 0f),
          "alternatives to frobnicator pro that are self hosted" to floatArrayOf(0.96f, 0.28f, 0f),
        ),
      )
    val first = guide.ask("self-hosted alternatives to Frobnicator-Pro", null)
    assertEquals(listOf("Frobnicator-Pro"), first.subjects)
    assertEquals(listOf("self-hosted"), first.constraints)

    val reused = guide.ask("alternatives to FROBNICATOR PRO that are Self Hosted", null)
    val again = guide.ask("alternatives to FROBNICATOR PRO that are Self Hosted", null)

    assertEquals(1, spelled.chooses, "the second question is answered from the first one's answer")
    assertEquals(listOf(1.0, 1.0), listOf(cache("similar"), cache("exact")))
    listOf(reused, again).forEach { answer ->
      assertEquals(listOf("FROBNICATOR PRO"), answer.subjects)
      assertEquals(listOf("Self Hosted"), answer.constraints)
      assertEquals(
        listOf("FROBNICATOR PRO"),
        answer.notices.filter { it.code == NoticeCode.GUIDE_SUBJECT_NOT_FOUND }.map { it.subject },
      )
      assertEquals(
        listOf("Self Hosted"),
        answer.recommendations.flatMap { it.checks }.map { it.constraint }.distinct(),
      )
      val everyField = answer.toString()
      assertFalse("Frobnicator-Pro" in everyField, "the first asker's spelling of the name")
      assertFalse("self-hosted" in everyField, "the first asker's spelling of the constraint")
    }
  }

  @Test
  fun `a zero-width or full-width variant of a question gives the model the same text as the plain one`() {
    val plain = "a self-hosted alternative to Frobnicator-Pro"
    val plainReply = "the desktop one"
    val variants =
      listOf(
        "a self-hosted alter\u200Bnative to Frobni\u2060cator-Pro\uFEFF" to "the desk\u200Dtop one",
        "ａ ｓｅｌｆ-ｈｏｓｔｅｄ alternative to Ｆｒｏｂｎｉｃａｔｏｒ-Ｐｒｏ" to "the ｄｅｓｋｔｏｐ one",
        "\u200B a self-hosted alternative to\u3000Frobnicator-Pro " to " the desktop one\u200B",
      )
    fun readOf(question: String, reply: String): List<String> {
      val spelled = SpelledAsAsked()
      guideWith(spelled).ask(question, null, clarification = reply)
      return spelled.read
    }

    val readPlain = readOf(plain, plainReply)

    assertTrue(plain in readPlain && plainReply in readPlain, "the model is asked: $readPlain")
    variants.forEach { (question, reply) ->
      assertEquals(
        GuideAnswerCache.key(plain, null, "config", plainReply),
        GuideAnswerCache.key(question, null, "config", reply),
        "the variant shares the plain question's key",
      )
      assertEquals(readPlain, readOf(question, reply), "what the model reads of \"$question\"")
    }
  }
}
