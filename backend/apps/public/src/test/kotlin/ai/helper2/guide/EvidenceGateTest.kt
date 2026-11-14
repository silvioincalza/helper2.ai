package ai.helper2.guide

import ai.helper2.guide.GuideValidator.Rejection
import ai.helper2.guide.usecase.AskGuide
import ai.helper2.search.CachedHits
import ai.helper2.search.NOW
import ai.helper2.search.ResultMerger
import ai.helper2.search.ResultObserver
import ai.helper2.search.SearchCache
import ai.helper2.search.SearchSource
import ai.helper2.search.SourceHit
import ai.helper2.search.SourceQuery
import ai.helper2.search.contexts
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
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory

class EvidenceGateTest {

  private val clock = Clock.fixed(NOW, ZoneOffset.UTC)
  private val executor = Executors.newVirtualThreadPerTaskExecutor()
  private val validator = GuideValidator()

  @AfterTest fun shutdown() = executor.shutdownNow().let {}

  private enum class Path(val maxChars: Int) {
    PICKS(GuideValidator.MAX_REASON_CHARS),
    COMPARE(AnswerLimits.DEFAULT.maxCellChars),
    EXPLAIN(AnswerLimits.DEFAULT.maxSentenceChars),
  }

  private class Claim(
    val name: String,
    val expected: Rejection?,
    val text: (Int) -> String = { "Runs large language models on your own machine." },
    val quote: String = OLLAMA_QUOTE,
    val title: String = "ollama",
    val description: String = OLLAMA,
    val paths: Set<Path> = Path.entries.toSet(),
  ) {
    val candidateText = "$title\n$description"
  }

  private data class Outcome(val kept: Int, val rejected: Int, val removed: List<TrailCheck>)

  private val claims =
    listOf(
      Claim("a text with a quote of the candidate's own words is kept", null),
      Claim(
        "a quote is matched without case, line breaks and repeated spaces",
        null,
        quote = "  GET UP and   running\nwith large language models LOCALLY  ",
      ),
      Claim(
        "single guillemets in a quote match angle brackets in the text",
        null,
        quote = "Wrap a prompt in ‹system› marks to get up and running",
        description = "Wrap a prompt in <system> marks to get up and running with local models",
      ),
      Claim(
        "the whole of a text shorter than the minimum is a long enough quote",
        null,
        quote = "tiny-mcp MCP server for SQLite",
        title = "tiny-mcp",
        description = "MCP server for SQLite",
      ),
      Claim("a text as long as the limit is kept", null, text = { max -> "c".repeat(max) }),
      Claim(
        "a text with spaces around it is kept when the whole of it fits the limit",
        null,
        text = { "  Runs large language models on your own machine.  " },
      ),
      Claim(
        "a text that names its own candidate's title is kept even when the title reads as a host",
        null,
        text = { "ollama.ai runs large language models on your own machine." },
        title = "ollama.ai",
      ),
      Claim(
        "a quote as long as the limit is kept",
        null,
        quote = LONG.take(300),
        description = LONG,
      ),
      Claim(
        "the quote's length is counted without the spaces around it",
        null,
        quote = "  ${LONG.take(300)} \n",
        description = LONG,
      ),
      Claim("an empty text is no reason", Rejection.NO_REASON, text = { "" }),
      Claim("a text of spaces is no reason", Rejection.NO_REASON, text = { " \n " }),
      Claim(
        "an empty text is no reason before its missing quote is read",
        Rejection.NO_REASON,
        text = { "" },
        quote = "",
      ),
      Claim(
        "a text one character over the limit is too long",
        Rejection.REASON_TOO_LONG,
        text = { max -> "c".repeat(max + 1) },
      ),
      Claim(
        "the text's length is counted with the spaces around it",
        Rejection.REASON_TOO_LONG,
        text = { max -> "c".repeat(max) + " " },
      ),
      Claim(
        "a text over the limit is too long before its link is read",
        Rejection.REASON_TOO_LONG,
        text = { max -> "https://evil.example " + "c".repeat(max) },
      ),
      Claim(
        "a text with a link is invalid",
        Rejection.REASON_INVALID,
        text = { "See https://evil.example for the fastest runner." },
      ),
      Claim(
        "a text with a bare host name is invalid",
        Rejection.REASON_INVALID,
        text = { "Install it from evil-tools.io today." },
      ),
      Claim(
        "a host name that is not the candidate's title is invalid",
        Rejection.REASON_INVALID,
        text = { "ollama.ai runs large language models on your own machine." },
      ),
      Claim(
        "a text with a link is invalid before its quote is read",
        Rejection.REASON_INVALID,
        text = { "See https://evil.example for more." },
        quote = "words the candidate never wrote anywhere at all",
      ),
      Claim(
        "a quote one token over the limit is too long, even when the text holds it",
        Rejection.QUOTE_TOO_LONG,
        quote = LONG.take(305),
        description = LONG,
      ),
      Claim(
        "a quote over the limit is too long before it is looked up",
        Rejection.QUOTE_TOO_LONG,
        quote = "z".repeat(301),
      ),
      Claim("an empty quote is not in the text", Rejection.QUOTE_NOT_IN_TEXT, quote = ""),
      Claim("a quote of spaces is not in the text", Rejection.QUOTE_NOT_IN_TEXT, quote = "  \n "),
      Claim(
        "words the candidate never wrote are not in the text",
        Rejection.QUOTE_NOT_IN_TEXT,
        quote = "the fastest runner available anywhere on earth today",
      ),
      Claim(
        "a quote that starts inside a word is not in the text",
        Rejection.QUOTE_NOT_IN_TEXT,
        quote = "et up and running with large language models locally",
      ),
      Claim(
        "a quote that ends inside a word is not in the text",
        Rejection.QUOTE_NOT_IN_TEXT,
        quote = "Get up and running with large language models local",
      ),
      Claim(
        "a quote of a few words is too short",
        Rejection.QUOTE_TOO_SHORT,
        quote = "MIT License",
      ),
      Claim(
        "six words under 40 characters are too short",
        Rejection.QUOTE_TOO_SHORT,
        quote = "up and running with large language",
      ),
      Claim(
        "40 characters in fewer than six words are too short",
        Rejection.QUOTE_TOO_SHORT,
        quote = "Internationalization infrastructure for multilingual applications",
        description =
          "Internationalization infrastructure for multilingual applications, with pluralization",
      ),
      Claim(
        "a part of a text shorter than the minimum is too short",
        Rejection.QUOTE_TOO_SHORT,
        quote = "MCP server for SQLite",
        title = "tiny-mcp",
        description = "MCP server for SQLite",
      ),
    )

  @TestFactory
  fun `the three paths of the guide give one verdict on a claim`(): List<DynamicTest> =
    claims.flatMap { claim ->
      claim.paths.map { path ->
        dynamicTest("${path.name.lowercase()}: ${claim.name}") {
          assertEquals(expectedOutcome(claim), outcomeOn(path, claim))
        }
      }
    }

  @TestFactory
  fun `picks count a refused claim under its own code`(): List<DynamicTest> =
    claims
      .filter { Path.PICKS in it.paths }
      .map { claim ->
        dynamicTest("picks: ${claim.name}") {
          assertEquals(listOfNotNull(claim.expected), validated(claim).rejectedBy.keys.toList())
        }
      }

  @TestFactory
  fun `the gate gives the verdict of the three paths, for the limit of each`(): List<DynamicTest> =
    claims.flatMap { claim ->
      claim.paths.map { path ->
        dynamicTest("gate, ${path.name.lowercase()} limit: ${claim.name}") {
          assertEquals(
            claim.expected,
            validator.evidenceGate.claimProblem(
              claim.text(path.maxChars),
              claim.quote,
              mergedCandidate(claim),
              path.maxChars,
            ),
          )
        }
      }
    }

  @Test
  fun `the validator's fingerprint, which keys the stored answers, is the one before the gate had its own class`() {
    assertEquals("3/40/6/300/400/5/words/secrets", validator.fingerprint)
  }

  @Test
  fun `the six codes of a refused claim are two checks in the trail`() {
    assertEquals(
      mapOf(
        Rejection.NO_REASON to TrailCheck.REASON_NOT_SUPPORTED,
        Rejection.REASON_TOO_LONG to TrailCheck.REASON_NOT_SUPPORTED,
        Rejection.REASON_INVALID to TrailCheck.REASON_NOT_SUPPORTED,
        Rejection.QUOTE_TOO_LONG to TrailCheck.QUOTE_NOT_FOUND,
        Rejection.QUOTE_NOT_IN_TEXT to TrailCheck.QUOTE_NOT_FOUND,
        Rejection.QUOTE_TOO_SHORT to TrailCheck.QUOTE_NOT_FOUND,
      ),
      claims.mapNotNull { it.expected }.distinct().associateWith { it.trailCheck },
    )
  }

  @Test
  fun `a claim about a candidate nobody retrieved is counted by picks and explanations, ignored by comparisons, and listed by none`() {
    val claim = Claim("unknown candidate", null)

    val picked =
      validator.validate(
        GuideProposal(listOf(ProposedPick("c99", claim.text(0), claim.quote))),
        listOf(mergedCandidate(claim)),
      )
    val compared =
      asked(claim, "Ollama vs LM Studio", Proposing(claim, Path.COMPARE, key = { "s99" }))
    val explained = asked(claim, "what is Ollama?", Proposing(claim, Path.EXPLAIN, key = { "c99" }))

    assertEquals(mapOf(Rejection.UNKNOWN_CANDIDATE to 1), picked.rejectedBy)
    assertEquals(emptyList(), validator.removedOf(picked))
    assertEquals(Outcome(kept = 0, rejected = 0, removed = emptyList()), comparisonOf(compared))
    assertEquals(Outcome(kept = 0, rejected = 1, removed = emptyList()), explanationOf(explained))
  }

  @Test
  fun `a kept claim is shown without the spaces around its text and its quote`() {
    val claim =
      Claim(
        "spaces around",
        null,
        text = { "  Runs models on your own machine.  " },
        quote = "  $OLLAMA_QUOTE  ",
      )

    val pick = validated(claim).picks.single().pick
    val cell =
      asked(claim, "Ollama vs LM Studio", Proposing(claim, Path.COMPARE))
        .comparison!!
        .rows
        .single()
        .cells
        .single { it.text != null }
    val sentence =
      asked(claim, "what is Ollama?", Proposing(claim, Path.EXPLAIN))
        .explanation!!
        .sentences
        .single()

    val shown = "Runs models on your own machine." to OLLAMA_QUOTE
    assertEquals(shown, pick.reason to pick.quote)
    assertEquals(shown, cell.text to cell.quote)
    assertEquals(shown, sentence.text to sentence.quote)
  }

  private fun expectedOutcome(claim: Claim): Outcome =
    when (claim.expected) {
      null -> Outcome(kept = 1, rejected = 0, removed = emptyList())
      Rejection.NO_REASON,
      Rejection.REASON_TOO_LONG,
      Rejection.REASON_INVALID ->
        Outcome(kept = 0, rejected = 1, removed = listOf(TrailCheck.REASON_NOT_SUPPORTED))
      else -> Outcome(kept = 0, rejected = 1, removed = listOf(TrailCheck.QUOTE_NOT_FOUND))
    }

  private fun outcomeOn(path: Path, claim: Claim): Outcome =
    when (path) {
      Path.PICKS -> {
        val result = validated(claim)
        Outcome(result.picks.size, result.rejected, validator.removedOf(result).map { it.check })
      }
      Path.COMPARE -> comparisonOf(askedAbout(claim, path, "Ollama vs LM Studio"))
      Path.EXPLAIN -> explanationOf(askedAbout(claim, path, "what is Ollama?"))
    }

  private fun validated(claim: Claim): GuideValidator.Result {
    val candidate = mergedCandidate(claim)
    assertEquals(claim.candidateText, candidate.text)
    return validator.validate(
      GuideProposal(
        listOf(ProposedPick(candidate.key, claim.text(Path.PICKS.maxChars), claim.quote))
      ),
      listOf(candidate),
    )
  }

  private fun mergedCandidate(claim: Claim) =
    Candidate("c1", ResultMerger().merge(listOf(subjectHit(claim)), contexts("s")).single())

  private fun askedAbout(claim: Claim, path: Path, question: String): GuideAnswer {
    val model = Proposing(claim, path)
    val answer = asked(claim, question, model)
    assertEquals(claim.candidateText, model.read?.text)
    return answer
  }

  private fun asked(claim: Claim, question: String, model: GuideModel) =
    AskGuide(searchOf(claim), model, clock).ask(question, null)

  private fun comparisonOf(answer: GuideAnswer) =
    Outcome(
      answer.comparison!!.rows.sumOf { row -> row.cells.count { it.text != null } },
      answer.rejected,
      answer.trail.removed.map { it.check },
    )

  private fun explanationOf(answer: GuideAnswer) =
    Outcome(
      answer.explanation?.sentences.orEmpty().size,
      answer.rejected,
      answer.trail.removed.map { it.check },
    )

  private fun subjectHit(claim: Claim) =
    hit(
      "s",
      "https://github.com/acme/ollama",
      title = claim.title,
      type = ResourceType.PRODUCT,
      description = claim.description,
    )

  private fun searchOf(claim: Claim): SearchResources {
    val hits =
      listOf(
        subjectHit(claim),
        hit(
          "s",
          "https://lmstudio.ai",
          title = "LM Studio",
          rank = 1,
          type = ResourceType.PRODUCT,
          description = "Discover, download and run local LLMs on your computer with a desktop app",
        ),
      )
    val source =
      object : SearchSource {
        override val descriptor = descriptor("s")

        override fun search(query: SourceQuery): List<SourceHit> = hits
      }
    return SearchResources(listOf(source), NO_CACHE, ResultObserver { emptyMap() }, executor, clock)
  }

  private class Proposing(
    private val claim: Claim,
    private val path: Path,
    private val key: (Candidate) -> String = { it.key },
  ) : GuideModel {
    override val provider = "fake"
    override val fastModel = "fast"
    override val reasoningModel = "reasoning"
    var read: Candidate? = null

    override fun plan(request: String, path: AudiencePath?) =
      when (this.path) {
        Path.COMPARE ->
          GuideSearchPlan(
            "local LLM runner",
            intent = GuideIntent.COMPARE,
            subjects = listOf("Ollama", "LM Studio"),
          )
        else -> GuideSearchPlan("Ollama", intent = GuideIntent.WHAT_IS, subjects = listOf("Ollama"))
      }

    override fun choose(request: Need, candidates: List<Candidate>, constraints: List<String>) =
      GuideProposal(emptyList())

    override fun verifyReasons(request: Need, claims: List<ReasonClaim>) = claims.associate {
      it.key to ReasonVerdict(true)
    }

    override fun compare(request: Need, subjects: List<Candidate>): ComparisonProposal {
      val subject = subjectOf(subjects)
      return ComparisonProposal(
        listOf(
          ProposedRow(
            "Licence",
            listOf(ProposedCell(key(subject), claim.text(path.maxChars), claim.quote)),
          )
        )
      )
    }

    override fun explain(
      request: Need,
      subject: String,
      candidates: List<Candidate>,
    ): ExplanationProposal {
      val own = subjectOf(candidates)
      return ExplanationProposal(
        listOf(ProposedSentence(claim.text(path.maxChars), key(own), claim.quote))
      )
    }

    private fun subjectOf(candidates: List<Candidate>) =
      candidates.first { isSubject(it.result, "Ollama") }.also { read = it }
  }

  private companion object {
    const val OLLAMA = "Get up and running with large language models locally. MIT License"
    const val OLLAMA_QUOTE = "Get up and running with large language models locally."
    val LONG = "abcde " + (100..199).joinToString(" ") { "n$it" }

    val NO_CACHE =
      object : SearchCache {
        override fun get(key: String) = null

        override fun put(key: String, value: CachedHits, ttl: Duration) {}
      }
  }
}
