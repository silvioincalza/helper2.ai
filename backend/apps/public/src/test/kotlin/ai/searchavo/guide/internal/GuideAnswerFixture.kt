package ai.searchavo.guide.internal

import ai.searchavo.guide.Candidate
import ai.searchavo.guide.GuideAnswer
import ai.searchavo.guide.GuideModel
import ai.searchavo.guide.GuideProposal
import ai.searchavo.guide.GuideSearchPlan
import ai.searchavo.guide.Need
import ai.searchavo.guide.ProposedPick
import ai.searchavo.guide.usecase.AskGuide
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
import ai.searchavo.shared.ResourceType
import ai.searchavo.testing.MovableClock
import java.time.Duration
import java.util.concurrent.Executors

object GuideAnswerFixture {
  fun answer(): GuideAnswer {
    val clock = MovableClock(NOW)
    val executor = Executors.newVirtualThreadPerTaskExecutor()
    val source =
      object : SearchSource {
        override val descriptor = descriptor("hf")

        override fun search(query: SourceQuery): List<SourceHit> =
          listOf(
            hit(
              "hf",
              "https://huggingface.co/acme/whisper-small",
              title = "whisper-small",
              type = ResourceType.MODEL,
              license = "MIT",
              published = "2026-01-02T00:00:00Z",
              repo = "https://github.com/acme/whisper",
              description =
                "Speech recognition model you can self-host, released under the MIT License",
            ),
            hit(
              "hf",
              "https://huggingface.co/acme/transcribe-prompt",
              title = "transcribe-prompt",
              rank = 1,
              type = ResourceType.PROMPT,
              description = "Prompt to clean up a raw speech transcript into readable text",
            ),
          )
      }
    val noCache =
      object : SearchCache {
        override fun get(key: String) = null

        override fun put(key: String, value: CachedHits, ttl: Duration) {}
      }
    val model =
      object : GuideModel {
        override val provider = "fake"
        override val fastModel = "fast"
        override val reasoningModel = "reasoning"

        override fun plan(request: String, path: AudiencePath?) =
          GuideSearchPlan("speech recognition", listOf("MIT licence"))

        override fun choose(
          request: Need,
          candidates: List<Candidate>,
          constraints: List<String>,
        ): GuideProposal {
          val whisper = candidates.first { it.result.title == "whisper-small" }.key
          val other = candidates.first { it.result.title == "transcribe-prompt" }.key
          return GuideProposal(
            listOf(
              ProposedPick(whisper, "Self-hostable.", "Speech recognition model you can self-host"),
              ProposedPick(
                other,
                "A prompt.",
                "a quote that is nowhere in this candidate's own text",
              ),
            )
          )
        }
      }
    val search =
      SearchResources(listOf(source), noCache, ResultObserver { emptyMap() }, executor, clock)
    try {
      return AskGuide(search, model, clock)
        .ask("speech to text I can self-host, MIT licence", AudiencePath.BUILD_WITH_AI)
    } finally {
      executor.shutdownNow()
    }
  }
}
