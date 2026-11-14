package ai.helper2.guide.internal

import ai.helper2.guide.Candidate
import ai.helper2.guide.GuideAnswer
import ai.helper2.guide.GuideModel
import ai.helper2.guide.GuideProposal
import ai.helper2.guide.GuideSearchPlan
import ai.helper2.guide.Need
import ai.helper2.guide.ProposedPick
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
import ai.helper2.testing.MovableClock
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
